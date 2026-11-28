package com.herc.clsnews.agent;

/**
 * 重复点击时的三档判定：<b>缓存</b> / <b>全量</b> / <b>增量</b>。
 *
 * <p>这个类被单独拎出来，只有一个原因：<b>它是唯一能在花钱之前把成本定死的决策点</b>。
 * 把它写进 {@link AgentService} 里，每个分支就只能靠真模型验证 —— 一次错判一万 token；
 * 写成纯函数之后，七个分支全部是几毫秒的字符串与整数运算，测试可以随便造。
 *
 * <p>判定顺序见设计文档 §10.2，顺序本身有语义，不能重排：
 * <ol>
 *   <li>没有 base full → 全量。今天第一次，没有可增量的对象</li>
 *   <li>base 不是今天的 → 全量。昨天的结论整份作废，不拿它当底</li>
 *   <li>水位线相同 → 缓存。数据一个字没变，连"新增不足"都不必算</li>
 *   <li>新增 &lt; 3 条 → 缓存。为两条新闻重排一次主线，性价比为负</li>
 *   <li>新增 &gt; 65 条 → 全量。到了交叉点，增量的固定开销已经抵不过省下的正文</li>
 *   <li>今日增量已满 3 次 → 全量。防结论一代代漂成"市场情绪偏暖"这类废话</li>
 *   <li>其余 → 增量</li>
 * </ol>
 *
 * <p>第 3 步必须排在第 4 步之前：水位线相同时 {@code added} 必然也是 0，
 * 但"数据没变"比"新增 0 条不足 3 条"更准确地描述了用户看到的现象。
 *
 * <h2>三个阈值怎么来的</h2>
 * <ul>
 *   <li><b>3</b>：性价比判断。增量固定成本约 8600 字符（旧结论 + 旧标题 + 旧证据），
 *       为 1–2 条新闻付这笔钱，比直接返回旧结论还亏。</li>
 *   <li><b>65</b>：解出来的交叉点。全量成本随总量缓增（L1 上限固定，只有 L2 标题在涨）：
 *       {@code 16544 + 38n}；增量是固定部分加新增正文：{@code 8644 + 166n}。
 *       两者在 n ≈ 62 相交，取 65 留余量。</li>
 *   <li><b>3 次</b>：见 {@link #MAX_DELTA_PER_DAY} 的注释。</li>
 * </ul>
 */
public final class Strategy {

    /** 新增不足这个数就返回旧结论。 */
    public static final int MIN_ADDED = 3;

    /** 新增超过这个数，增量已不划算。交叉点 n ≈ 62，取 65 留余量。 */
    public static final int MAX_ADDED = 65;

    /**
     * 同日增量次数的上限。
     *
     * <p>delta 的输入是"旧结论 + 全部新增"，输出的仍是完整主线，所以每一代都在上一代的判断上
     * 再判断一次。链式更新会导致三代之后主线退化成正确的废话，且上一代编错的引用被继承下来。
     * {@code base_run_id} 永远指向 full 已经解决了精度问题，这个上限解决的是"一天被点太多次
     * 本身就不正常"—— 满 3 次强制全量，把结论重新拉回一次客观基准。
     */
    public static final int MAX_DELTA_PER_DAY = 3;

    /**
     * 给用户看的那句话的宽度上限，单位是「全角字」（半角按 0.5 计）。
     *
     * <p>不是拍脑袋定的：浮层标题区在 390 / 360 / 320 三档屏宽下可用宽只有
     * 290 / 264 / 224px（右边站着「刷新」按钮），11.5px 字号约合 25 / 22 / 19 个字。
     * 实测 20 字在 320px 上就折行，折出来的第二行只剩半句，比截断更难看。
     * 卡在 18 是给不同机型的中文字体渲染留一格余量。
     *
     * <p>这条是<b>接口契约</b>，不是风格建议：{@code Result.note} 直接渲染进浮层。
     * 超了 {@code NoteCopyTest} 会红。
     */
    public static final int MAX_NOTE_WIDTH = 18;

    /**
     * 数据一个字没变时的提示。
     *
     * <p>提成常量是因为它有两个出口：{@link #decide} 的 SAME_WATERMARK 分支，
     * 以及 {@code AgentService} 里"跨策略缓存比对直接命中"那条更早的返回路径
     * （那条根本没走到 decide）。两处必须是同一句话，否则用户同一次操作
     * 在不同路径下看到两种说法。
     */
    public static final String NOTE_SAME = "数据没有变化，直接返回上一版";

    /**
     * 用户明确点了「重新生成」。
     *
     * <p>不写「已更新」：这一版和上一版的数据可能完全一样，说"更新"会让人以为
     * 抓到了新内容。说的是"按你要求的又跑了一遍"，这是唯一确定的事实。
     */
    public static final String NOTE_FORCED = "已按你的要求重新生成";

    /** 走哪一档。 */
    public enum Mode {
        /** 不调模型，直接把已有结论返回。 */
        CACHE,
        /** 全量重算。 */
        FULL,
        /** 带旧结论只发增量。 */
        DELTA
    }

    /** 为什么这么判。写进日志，也是给用户看的那句话的依据。 */
    public enum Reason {
        /** 今天还没有成功的 full。 */
        NO_BASE,
        /** base 不是当天的（防御性判断，正常查询已经限定 window_key）。 */
        CROSS_DAY,
        /** 水位线与 base 相同，数据没动。 */
        SAME_WATERMARK,
        /** 新增 1–2 条，不值得重排。 */
        TOO_FEW_ADDED,
        /** 增量。 */
        DELTA,
        /** 新增太多，全量更划算。 */
        TOO_MANY_ADDED,
        /** 今日增量次数已满。 */
        DELTA_LIMIT
    }

    /**
     * 增量所基于的那次 full。
     *
     * @param runId    {@code agent_run.id}，写进新 run 的 {@code base_run_id}
     * @param watermark 那次生成时的水位线，用来切分"旧条目"与"新增"
     * @param windowKey 那次生成所属的日期，跨天判定用
     */
    public record Base(String runId, long watermark, String windowKey) {
    }

    /**
     * 判定的全部输入。四个字段都能在测试里直接构造，不需要数据库。
     *
     * @param windowKey  本次生成所属的日期
     * @param watermark  当前 {@code max(news.id)}
     * @param base       当日最近一次成功的 full，null 表示没有
     * @param added      候选集里 {@code id > base.watermark} 的条数；无 base 时传 0
     * @param deltaToday 今天这个模板（同参数）已经成功跑过几次 delta
     * @param deltaLimit 上限，默认 {@link #MAX_DELTA_PER_DAY}；测试可以调小
     */
    public record Input(String windowKey, long watermark, Base base, int added,
                        int deltaToday, int deltaLimit) {

        public Input {
            if (deltaLimit <= 0) {
                deltaLimit = MAX_DELTA_PER_DAY;
            }
        }

        /** 参数最多的构造方式，阈值取默认。 */
        public static Input of(String windowKey, long watermark, Base base, int added, int deltaToday) {
            return new Input(windowKey, watermark, base, added, deltaToday, MAX_DELTA_PER_DAY);
        }
    }

    /**
     * @param mode  走哪一档
     * @param reason 判定依据
     * @param base   增量时要带的旧结论；CACHE 且命中旧结论时也非 null，供调用方取结果
     * @param note   给用户看的一句话，中文，宽度见 {@link #MAX_NOTE_WIDTH}。
     *               详细依据（阈值、日期、次数）看 {@code reason} 与日志，别往这句话里塞
     */
    public record Decision(Mode mode, Reason reason, Base base, String note) {
    }

    private Strategy() {
    }

    /**
     * 判定。纯函数：同样的输入永远同样的输出，不读时钟、不碰数据库。
     *
     * <p>{@code added} 会被夹到非负：水位线单调递增，正常不会出现负数，
     * 但一旦出现（比如手工改库、或 base 来自未来某次导入），负数会让 {@code < 3} 命中，
     * 静默返回一份旧结论 —— 这比报错更难查。夹成 0 之后语义退化成"没新增"，是对的。
     */
    public static Decision decide(Input in) {
        int n = Math.max(in.added(), 0);

        if (in.base() == null) {
            return new Decision(Mode.FULL, Reason.NO_BASE, null,
                    "今天还没生成过，跑一次全量");
        }
        Base base = in.base();

        if (!base.windowKey().equals(in.windowKey())) {
            return new Decision(Mode.FULL, Reason.CROSS_DAY, null,
                    "上一版不是今天的，重新全量");
        }

        if (in.watermark() == base.watermark()) {
            return new Decision(Mode.CACHE, Reason.SAME_WATERMARK, base, NOTE_SAME);
        }

        if (n < MIN_ADDED) {
            return new Decision(Mode.CACHE, Reason.TOO_FEW_ADDED, base,
                    "新增 " + n + " 条，未达阈值，先看上一版");
        }

        if (n > MAX_ADDED) {
            return new Decision(Mode.FULL, Reason.TOO_MANY_ADDED, base,
                    "新增 " + n + " 条，太多，这次走全量");
        }

        if (in.deltaToday() >= in.deltaLimit()) {
            return new Decision(Mode.FULL, Reason.DELTA_LIMIT, base,
                    "今日增量已满 " + in.deltaToday() + " 次，这次走全量");
        }

        return new Decision(Mode.DELTA, Reason.DELTA, base,
                "新增 " + n + " 条，带旧结论只发增量");
    }
}
