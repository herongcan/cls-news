package com.herc.clsnews.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.model.News;
import com.herc.clsnews.store.NewsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 分析能力的唯一入口。手动点击和后台调度都走这里，区别只在谁来触发。
 *
 * <p>为什么必须从 {@link com.herc.clsnews.job.AnalysisWorker} 里抽出来：
 * Worker 带 {@code @ConditionalOnProperty}，手动模式下整个 Bean 不创建，
 * 那么写在它构造函数里的「启动时把 analyzing 打回 pending」也跟着消失。
 * 进程被杀一次就会留下永远停在 analyzing 的行，用户点击只会一直收到
 * 「正在分析中」——而那个「正在分析」的请求早就死了。这是把恢复逻辑
 * 放在一个可能不存在的 Bean 里的必然结果。
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    /**
     * 结果多新算「不用重算」。
     * 这条规则挡的不是技术问题，是人的问题：10 个同事共用一个账号，
     * 同一条消息谁都想点一下看看，没有这层保护就是 10 次调用。
     * 真要重算由运维带 force=true 手工调接口 —— 界面故意不提供这个入口：
     * 同样的输入再喂一遍模型，答案基本不会变，点一次只是多花一次钱。
     */
    private static final long FRESH_SECONDS = 90;

    private final NewsStore store;
    private final Analyzer analyzer;
    private final AppProps props;
    private final ObjectMapper mapper;

    public AnalysisService(NewsStore store, Analyzer analyzer, AppProps props, ObjectMapper mapper) {
        this.store = store;
        this.analyzer = analyzer;
        this.props = props;
        this.mapper = mapper;
        int stuck = store.resetStuck();
        if (stuck > 0) {
            log.warn("发现 {} 条上次未完成的分析，状态已复位，可重新点击分析", stuck);
        }
        // 调用记录只为算「今日用了多少」，留 7 天足够排查一次异常消费，再长就是纯垃圾
        int pruned = store.pruneCalls(System.currentTimeMillis() / 1000 - 7L * 24 * 3600);
        if (pruned > 0) {
            log.info("清理 {} 条过期的大模型调用记录", pruned);
        }
    }

    /** @param item 落库后的最新状态；@param cached true = 命中旧结果，没有调用模型 */
    public record Outcome(News item, boolean cached) {
    }

    public boolean enabled() {
        return props.getAnalysis().isEnabled();
    }

    /** 能不能真的干活：开关开着 + key 配好了。false 时点击应该直接拒绝，而不是逐条去撞墙。 */
    public boolean ready() {
        return enabled() && analyzer.ready();
    }

    /**
     * 今日已调用模型的次数（不是「已分析的行数」）。
     *
     * <p>为什么按调用次数算配额：失败重试和运维手工重算会让同一行被调用多次，
     * 按「已分析的行数」计永远撞不到上限，而每次都是真金白银的一次请求。
     * 查表而不是内存计数器，重启和多实例都准。
     *
     * @param actor null = 全站总次数（主账号看到的数字）；非 null = 只数这个角色花的
     */
    public long usedToday(String actor) {
        long startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        return store.countCallsSince(startOfDay, actor);
    }

    public long usedToday() {
        return usedToday(null);
    }

    public int dailyCap() {
        return props.getAnalysis().getDailyCap();
    }

    public boolean capped() {
        return usedToday() >= dailyCap();
    }

    /**
     * 这个角色现在还能不能分析。
     *
     * <p>两层都要判：全局 daily-cap 管总量（花多少钱），角色额度管分配。
     * 只判角色额度的话，游客能突破全站上限。
     *
     * @param actorCap null = 该角色没有独立额度，只受全局上限约束
     */
    public boolean capped(String actor, Integer actorCap) {
        if (usedToday() >= dailyCap()) {
            return true;
        }
        return actorCap != null && usedToday(actor) >= actorCap;
    }

    /** 当前模型标识，给 /api/status 看，也用于判断线上到底跑的是哪个模型。 */
    public String modelName() {
        return analyzer.modelName();
    }

    /** 本轮进程的用量摘要。会随重启清零，只作参考；准数看 usedToday()。 */
    public String usage() {
        return analyzer.usage();
    }

    /**
     * 分析一条消息。
     *
     * <p>语义是「用户点了就出结果」，所以三种情况不调模型：
     * 已经分析过且结果新鲜（返回旧结果）、有人正在分析（BUSY）、今日配额用完（CAPPED）。
     *
     * @param force    true 时无视新鲜度强制重算，只给运维手工调用，界面不暴露
     * @param actor    谁触发的，记账用（rw / guest / ro / auto）。null 当 rw
     * @param actorCap 该角色的独立日额度，null = 只受全局 daily-cap 约束
     * @throws AnalysisException 所有可预期的失败都从这里出去，带状态码和错误码
     */
    public Outcome analyzeNews(long id, boolean force, String actor, Integer actorCap) {
        if (!enabled()) {
            throw new AnalysisException(503, "DISABLED", "分析功能已暂停");
        }
        if (!analyzer.ready()) {
            // 这句话会原样铺在页面上给同事看（前端把 503 的 message 当提示条），
            // 所以不写 DEEPSEEK_API_KEY、app.analysis.enabled 这类运维词 —— 看到它们的人
            // 既改不了配置也不知道该找谁。排障信息在启动日志（LlmKey 那两行）与
            // /api/agent/health 的 problems 里，那里才是该看的地方；code=NOT_READY 留给脚本判断。
            throw new AnalysisException(503, "NOT_READY", "分析功能暂不可用，稍后再试");
        }

        News before = store.findById(id)
                .orElseThrow(() -> new AnalysisException(404, "NOT_FOUND", "id 不存在：" + id));

        if (!force && isFresh(before)) {
            return new Outcome(before, true);
        }

        // 栏目导流（【风口研报】【盘中宝】这类）正文是荐股话术，结论必然是"利好某某板块"，
        // 分析它等于花钱买一句广告。位置很关键：
        //   放在 isFresh 之后 —— 历史上已经分析过的照常返回缓存，不因为新规则把旧结果藏起来；
        //   放在配额判断之前 —— 被拦的这条不扣次数，也不占别人的额度。
        if (NewsClass.isPromo(before.title())) {
            throw new AnalysisException(400, "PROMO_ITEM",
                    "该条是栏目导流（【…】开头），正文没有可分析的信息，建议直接看原文");
        }

        if (usedToday() >= dailyCap()) {
            // 必须明说，否则用户只会重复点，把次数白耗在上限判断上。
            // 「调 app.analysis.daily-cap 可放宽」这句删了：看页面的人是同事，不是改配置的人
            throw new AnalysisException(429, "CAPPED",
                    "今日分析已达上限 " + dailyCap() + " 条，明天再试");
        }
        if (actorCap != null && usedToday(actor) >= actorCap) {
            // 文案要点明是哪个上限：游客看到「已达上限 300 条」会以为是系统坏了
            throw new AnalysisException(429, "CAPPED",
                    "游客账号今日 " + actorCap + " 次分析已用完，明天再试");
        }

        // 原子认领：两个人同时点同一条，只有一个拿到这行，另一个拿空 → BUSY。
        News claimed = store.claimOne(id).orElseThrow(
                () -> new AnalysisException(409, "BUSY", "这条正在分析中，稍等几秒"));

        // 先记账再调用：调用失败时 token 往往也已经花出去了，
        // 事后记会让「一直失败一直重试」变成不计数量的黑洞。
        store.recordCall(id, actor);

        try {
            AnalysisResult r = analyzer.analyze(claimed);
            store.saveAnalysis(id, mapper.writeValueAsString(r), r.model());
            log.info("分析完成 id={} sentiment={} conf={} cached=false",
                    id, r.sentiment(), r.confidence());
            return new Outcome(store.findById(id).orElse(claimed), false);
        } catch (Exception e) {
            store.markFailed(id);
            log.warn("分析失败 id={}：{}", id, e.getMessage());
            // 原来把 e.getMessage() 原样回给前端，页面上就会出现 "Read timed out"、
            // 上游 401 的 JSON 片段这类东西 —— 用户读不懂，也没法据此做任何事。
            // 原始信息留在上面这行 warn 里（按 id 能查到是哪条），页面只给一句能理解的。
            throw new AnalysisException(502, "MODEL_ERROR", "模型返回异常，稍后再试");
        }
    }

    private boolean isFresh(News n) {
        if (!"done".equals(n.analysisStatus())) {
            return false;
        }
        Long at = n.analyzedAt();
        if (at == null) {
            return false;
        }
        return System.currentTimeMillis() / 1000 - at < FRESH_SECONDS;
    }
}
