package com.herc.clsnews.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.herc.clsnews.config.AppProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 主线生成的编排。一次点击从这里进、从这里出。
 *
 * <p>步骤顺序是刻意的，每一步的位置都对应一条不能违反的规则：
 * <ol>
 *   <li><b>可用性</b>（模板/开关/key）→ 不可用就直接 503，一步不往下走</li>
 *   <li><b>配额</b> → 在取数之前判，因为取数虽然免费但会白等</li>
 *   <li><b>取数 + 打包</b> → 纯代码，无成本</li>
 *   <li><b>缓存比对</b> → <b>跨策略</b>先比一次（full / delta 都算候选），见 §10.2；
 *       在 guard 之前，因为同一批数据已经有结果时，没必要再判"材料够不够"</li>
 *   <li><b>guard 拒答</b> → 在调模型之前，这是省钱的关键位置（材料不足 + 主题不符两道）</li>
 *   <li><b>三档判定</b> → {@link Strategy}：缓存 / 全量 / 增量，纯函数，0 成本</li>
 *   <li><b>调模型</b> → 唯一花钱的一步</li>
 *   <li><b>解析校验</b> → 引用越界在这里被剥掉</li>
 *   <li><b>落库</b> → 撞唯一索引说明并发重了，返回赢家的结果</li>
 * </ol>
 *
 * <p><b>为什么第 4 步和第 6 步都要判缓存</b>：两者不是重复。第 4 步是"同一水位线已经算过"，
 * 一次查询就能定；第 6 步处理的是"数据变了但变得不多"—— 它需要先找到 base full，
 * 再算新增条数，比一次查询贵，但比一次模型调用便宜三个数量级。
 *
 * <h2>S1.3 的范围</h2>
 * 三档全部落地。delta 建立在 full 之上（{@code base_run_id} 永远指向 full，不链式），
 * 且同一批数据 full 与 delta 互斥（唯一索引不含 strategy），缓存里不会出现两份矛盾的今日主线。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter ID_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZONE);

    /** 一天的秒数。窗口对齐到自然日，见 {@link #ask} 的注释。 */
    private static final long DAY = 86_400L;

    /*
     * 下面几句是**直接渲染进浮层**的文案，宽度受 Strategy.MAX_NOTE_WIDTH 约束。
     *
     * 提成常量而不是写在方法里，只有一个原因：能测。这些句子全部在分支深处，
     * 写成内联字面量就只能靠真模型跑一次才发现"这句话在手机上折成两行"，
     * 而 18 个字的上限是可以用几毫秒验证的（见 NoteCopyTest）。
     *
     * 字数的细节（字符数、条数、日期）留给日志，别塞进这几句。
     */

    /** 成本闸：算出来增量不比全量短，降级全量。 */
    static final String NOTE_COST_GATE = "增量不比全量省，这次走全量";

    /** 判定成增量但上一版结论读不出来，降级全量。 */
    static final String NOTE_NO_PREV = "上一版读不出来，这次走全量";

    /** 骤减护栏回退。具体条数与命中哪条判据写在 markFailed 的 error 列里。 */
    static final String NOTE_ROLLBACK = "结果较上一版骤减，已回退上一版";

    /** brief: false 的模板，每次都必须新生成。 */
    static final String NOTE_NOT_CACHEABLE = "该模板不参与缓存与增量，每次全量";

    private final AgentCatalog catalog;
    private final Retrieval retrieval;
    private final AgentRunStore runs;
    private final AgentLlm llm;
    private final AnswerParser parser;
    private final SingleFlight flights;
    private final AppProps props;
    private final ObjectMapper mapper;

    public AgentService(AgentCatalog catalog, Retrieval retrieval, AgentRunStore runs,
                        AgentLlm llm, SingleFlight flights, AppProps props, ObjectMapper mapper) {
        this.catalog = catalog;
        this.retrieval = retrieval;
        this.runs = runs;
        this.llm = llm;
        this.flights = flights;
        this.props = props;
        this.mapper = mapper;
        this.parser = new AnswerParser(mapper);

        // 和单条分析同样的理由：running 是进程内状态，重启后不可能是真的还在跑。
        // 不清掉，界面上会永远显示"生成中"，而配额账上又看不到这一行。
        int stuck = runs.resetStuck();
        if (stuck > 0) {
            log.warn("发现 {} 次上次未完成的主线生成，状态已复位", stuck);
        }
    }

    public boolean enabled() {
        return props.getAgent().isEnabled();
    }

    /**
     * 生成一次主线。
     *
     * <p><b>窗口对齐到自然日，不用滚动 24 小时。</b>这是个容易被忽略但会破坏缓存的细节：
     * 若窗口是 {@code [now-1d, now]}，每次点击的区间都在变，旧新闻会滑出窗口，
     * 候选集变了而 {@code watermark}（max id）没变 —— 缓存会误判成"数据没动"，
     * 返回一份少了若干条的旧结论。按自然日切分后，同一天内的窗口恒定，
     * watermark 才真正等价于"数据有没有变"。
     *
     * @param templateId 模板 id
     * @param keywords   额外关键词，null/空表示用模板自带的
     * @param actor      谁触发的，记账与审计用
     * @param actorCap   该角色的独立日额度，null = 只受全站上限约束（由接口层按角色传入）
     */
    public Result ask(String templateId, String keywords, String actor, Integer actorCap) {
        return ask(templateId, keywords, false, actor, actorCap, Proc.NOOP);
    }

    /**
     * @param force 越过「同水位线缓存」与「新增不足」两处短路，按当前数据重新生成一次。
     *              <p>只给一个场景用：屏上那版结论没有过程数据（过程字段上线前生成的），
     *              而缓存判定会让每次点击都返回同一版，用户就永远看不到过程。
     *              这不是"重试"—— 重试应该免费；这是明确的"再跑一次"，所以要付费、要提示价格。
     */
    public Result ask(String templateId, String keywords, boolean force, String actor, Integer actorCap) {
        return ask(templateId, keywords, force, actor, actorCap, Proc.NOOP);
    }

    /**
     * 带过程推送的版本。SSE 接口走这个，{@code /api/agent/ask} 走上面那个（sink = NOOP）。
     *
     * <p><b>除了多一个 sink，行为完全一致</b>：判定的顺序、缓存的口径、失败时记多少 token，
     * 全部走同一段代码。这不是洁癖 —— 若流式版本另写一条"边算边报"的路径，两条路的
     * 判定迟早分叉，界面上出现"流式说走增量、实际按全量扣钱"这种对不上的账。
     *
     * @param sink 每完成一步就被调一次。<b>它抛异常不会中断生成</b>，理由见 {@link Proc}
     */
    public Result ask(String templateId, String keywords, boolean force, String actor, Integer actorCap,
                      Proc.Sink sink) {
        Proc proc = new Proc(sink);
        AppProps.Agent cfg = props.getAgent();
        AgentTemplate t = available(templateId, cfg);
        if (!llm.ready()) {
            throw new AgentException(503, "NOT_READY", "模型未就绪，检查 API key 是否配置");
        }

        Prepared in = scan(t, keywords);
        // 取数这四行在 scan 返回时就已经是真的了（本地查询，几十毫秒）。
        // 之前它们要等到生成结束才随结果一起出现在界面上，等于把"已经知道的事"压了十几秒 ——
        // 流式最直接的收益就是把这一段提前，而不是等模型那一步。
        proc.addAll(Steps.fromScan(in.pack()));
        checkQuota(cfg, in.dayStart(), actor, actorCap);

        // §10.2 第一步：跨策略比对。full 与 delta 的输出形态相同，都算缓存候选。
        // 反过来（先找"最近一次 full"再比 watermark）会漏：delta 推进了水位线之后，
        // 数据没变用户再点，水位数与 full 不等，会被判成"要再跑一次 delta"——又扣一次费。
        if (force) {
            log.info("主线强制重算 template={} watermark={}（跳过同水位线缓存）", t.id(), in.watermark());
        } else {
            Optional<AgentRunStore.RunRow> hit = findHit(t, in);
            if (hit.isPresent()) {
                log.info("主线命中同水位线结果 template={} watermark={} run={}",
                        t.id(), in.watermark(), hit.get().id());
                // note 不能给 null：这是"用户又点了一次、结果没变"最常见的路径，
                // 而浮层正是靠这句话回答"为什么这次没重算"。null 的话界面上什么都不说，
                // 用户只会以为按钮坏了（S1.4 原型确认过这个场景）。
                //
                // 命中缓存也要把「上次那份完整过程」推出去：scan 已经推了「取数/打包」两行，
                // 不补全的话，流式界面会停在"取数 2 行 → 戛然而止"，看着像过程跑到一半断了。
                // 这里用上次落库的 steps 整段替换，和 note 的「数据没有变化」自洽。
                List<Step> cachedSteps = readSteps(hit.get());
                if (!cachedSteps.isEmpty()) {
                    proc.reset(cachedSteps);
                }
                return toResult(hit.get(), true, Strategy.NOTE_SAME);
            }
        }

        PackResult pack = in.pack();
        Guard.Verdict verdict = Guard.check(t, pack);
        if (verdict.rejected()) {
            String runId = newRunId();
            // 一步写到终态。rejected 不在唯一索引的条件里，所以即使此刻别人正在跑同一批数据，
            // 这条"材料不足"的结论也写得进去；走 running 会撞索引，把一个和钱无关的
            // 结论（0 token）变成 500。
            runs.insertRejected(runId, t.id(), in.params(), actorOr(actor), in.windowKey(), "full",
                    in.watermark(), verdict.reason() + "：" + verdict.detail(), pack.candidates());
            runs.insertStep(runId, 1, "guard", 0, null, 0, 0, 0, verdict.detail());
            // 拒答也要留过程：「材料校验 不足」这一行正是用户要的答案，
            // 只给一句 detail 的话，他看不出是在 69 条里判不足，还是压根没取到数据。
            // 取数那几行 scan 之后就进了 proc（也推给了界面），这里只补最后一行
            List<Step> steps = proc.add(Steps.guard(verdict)).list();
            runs.stampSteps(runId, json(steps));
            log.info("主线拒答 template={} reason={} candidates={} （0 token）",
                    t.id(), verdict.reason(), pack.candidates());
            return new Result(runId, t.id(), "rejected", "full", t.title(), null,
                    verdict.reason(), verdict.detail(), null, in.watermark(), pack.candidates(),
                    0, 0, now(), false, llm.modelName(), steps);
        }

        // §10.2 第二步：三档判定。纯函数，不花钱，但决定了下面要不要花、花多少。
        GenCtx ctx;
        if (force) {
            // 强制重算：旧记录先让位（唯一索引覆盖 succeeded），再按全量跑一次。
            // 不让位的话，claim 会读到那条 succeeded 并直接返回它 —— 用户点了「重新生成」，
            // 拿回来的还是同一版，一个字都没变，而且不会报任何错。
            int freed = runs.supersede(t.id(), in.windowKey(), in.watermark(), in.params());
            ctx = new GenCtx(Strategy.Mode.FULL, Strategy.NOTE_FORCED,
                    null, null, 0, null, 0, 0, 0, null);
            log.info("主线强制重算 template={} watermark={}（让位 {} 条旧记录，按全量重跑）",
                    t.id(), in.watermark(), freed);
        } else {
            ctx = prepare(t, pack, in.params(), in.windowKey(), in.watermark(), in.from(), in.to());
        }
        if (ctx.mode() == Strategy.Mode.CACHE) {
            log.info("主线按新增不足返回上一版 template={} watermark={} base={} {}",
                    t.id(), in.watermark(), ctx.baseRow().id(), ctx.note());
            // 同 findHit 缓存命中的处理：scan 已推了取数几行，这里补全成上次的完整过程
            List<Step> cachedSteps = readSteps(ctx.baseRow());
            if (!cachedSteps.isEmpty()) {
                proc.reset(cachedSteps);
            }
            return toResult(ctx.baseRow(), true, ctx.note());
        }
        log.info("主线策略 template={} strategy={} {} {}", t.id(), ctx.mode(), ctx.note(), pack.summary());

        // 方案 B：同进程的并发点击合并成一次调用。
        // key 必须和缓存键同构 —— 少一个维度，本该分开的两次生成会互相顶掉；
        // 不同的 key 硬要合并，又会让用户拿到别人关键词的结果。
        // 不含 strategy：同键 full 与 delta 本来就必须互斥（唯一索引也是这么定的），
        // 而且判定是确定性的，两个并发请求不会一个判 delta 一个判 full。
        String flightKey = t.id() + '|' + in.windowKey() + '|' + in.watermark() + '|' + in.params();
        SingleFlight.Waited<Result> waited = flights.run(flightKey,
                // 比模型超时多 5 秒：等待上限必须比被等的事情长，否则必然先超时
                cfg.getTimeoutMs() + 5_000L,
                () -> generate(t, pack, in.params(), in.windowKey(), actor, in.watermark(),
                        in.from(), in.to(), ctx, proc));

        if (waited.follower()) {
            // 这次没有调模型，结果是别人的。对用户按"缓存命中"呈现，
            // 否则界面上会出现"两个人都点了生成，两个人都看到非缓存"，对不上账
            log.info("主线由并发请求合并 template={} watermark={} run={}",
                    t.id(), in.watermark(), waited.value().runId());
            return markCached(waited.value());
        }
        return waited.value();
    }

    /**
     * 报价：完全按 {@link #ask} 的同一套判定走一遍，只是不落库、不调模型。
     *
     * <p><b>为什么必须复用同一段代码而不是"大致算一下"</b>：浮层上写"这次走增量、约 6200 token"，
     * 用户据此决定点不点。报价和实际执行若是两套逻辑，偏差一旦出现，用户学到的就是
     * "那个数字不能信"——比不显示更糟。所以这里和 ask 共用 {@link #available} /
     * {@link #scan} / {@link #findHit} / {@link #prepare}，连判定顺序都一样。
     *
     * <p>与 ask 的三处刻意差异：
     * <ul>
     *   <li>不查配额，只报告 {@code remaining}：查配额是为了拦，而报价本身就是"还剩几次"</li>
     *   <li>模型没就绪也不抛 503，只在 {@code ready} 里如实说明 —— 今天已有的结论还能看，
     *       因为 key 过期就连旧结论都不给，是更糟的失败</li>
     *   <li>guard 的结论照实返回 {@code rejected}：材料不够时先把话说清楚，别让人点了才知道</li>
     * </ul>
     *
     * @param actorCap 该角色的独立日额度，null = 只受全站上限约束
     */
    public Precheck precheck(String templateId, String keywords, String actor, Integer actorCap) {
        AppProps.Agent cfg = props.getAgent();
        AgentTemplate t = available(templateId, cfg);
        Prepared in = scan(t, keywords);
        long dayStart = in.dayStart();
        int full = fullTokens(t, in);
        int left = remaining(cfg, dayStart, actor, actorCap);
        // 不因为 key 没配就抛 503：今天已经生成好的结论还能读，把它一起挡掉更糟。
        // 真实场景是 key 过期/被撤 —— 那时用户至少还看得到上午那份主线。
        boolean ready = llm.ready();

        AgentRunStore.RunRow cached = findHit(t, in).orElse(null);
        if (cached != null) {
            return new Precheck(t.id(), t.title(), t.cacheable(), t.window(), "cached", "cache", ready,
                    0, cached.candidates(), 0, full, in.watermark(), cached.createdAt(),
                    cached.id(), null, left, Strategy.NOTE_SAME, null, readSteps(cached));
        }

        Guard.Verdict verdict = Guard.check(t, in.pack());
        if (verdict.rejected()) {
            // 拒答没有结论可对应，过程就是「这次为什么判不足」
            List<Step> steps = Steps.fromScan(in.pack());
            steps.add(Steps.guard(verdict));
            return new Precheck(t.id(), t.title(), t.cacheable(), t.window(), "rejected",
                    verdict.reason(), ready, 0, in.pack().candidates(), 0, full, in.watermark(),
                    0, null, null, left, null, verdict.detail(), steps);
        }

        GenCtx ctx = prepare(t, in.pack(), in.params(), in.windowKey(), in.watermark(),
                in.from(), in.to());
        if (ctx.mode() == Strategy.Mode.CACHE) {
            AgentRunStore.RunRow row = ctx.baseRow();
            return new Precheck(t.id(), t.title(), t.cacheable(), t.window(), "cached", "too-few", ready,
                    0, row.candidates(), 0, full, in.watermark(), row.createdAt(),
                    row.id(), null, left, ctx.note(), null, readSteps(row));
        }

        // prepare 只在 DELTA 时把 base 带回来（FULL 时故意为 null，避免误用）。
        // 这里再查一次只为给界面一个"当前版本"：同一个索引，微秒级，
        // 换来的是打开浮层就能看到今天已有的主线，而不是一片空白加一个按钮。
        AgentRunStore.RunRow base = t.cacheable()
                ? runs.findBaseFull(t.id(), in.windowKey(), in.params()).orElse(null)
                : null;
        int estimate = ctx.mode() == Strategy.Mode.DELTA
                ? PromptBuilder.tokens(ctx.deltaPrompt().chars()) + Packer.PROMPT_OVERHEAD
                : full;
        return new Precheck(t.id(), t.title(), t.cacheable(), t.window(),
                ctx.mode() == Strategy.Mode.DELTA ? "delta" : "full", ctx.note(), ready,
                ctx.added(), in.pack().candidates(), estimate, full, in.watermark(),
                base == null ? 0 : base.createdAt(), null, base == null ? null : base.id(),
                left, ctx.note(), null, base == null ? List.of() : readSteps(base));
    }

    /**
     * 开流前的「能不能开始」判定：开关 / 模板 / key / 配额，四道门。
     *
     * <p><b>为什么 SSE 需要它而同步接口不需要</b>：同步接口抛异常时状态码还没发出去，
     * 直接变成 503 / 429。而 SSE 一旦把 emitter 交给 Spring，响应头就已经是 200 了 ——
     * 之后再想报"没额度"，只能塞进事件里，前端和监控看到的永远是 200，
     * 分不清"接口坏了"和"今天用完了"。
     *
     * <p>不取数、不落库、0 token，毫秒级。真正取数要等到开流之后 —— 那时才有地方推步骤。
     *
     * <p>和 {@link #ask} 头部的四项检查是同一段逻辑，故意重复：这里少判一项，
     * 用户就会看到流建立成功、然后第一个事件是错误。
     */
    public void preflight(String templateId, String actor, Integer actorCap) {
        AppProps.Agent cfg = props.getAgent();
        available(templateId, cfg);
        if (!llm.ready()) {
            throw new AgentException(503, "NOT_READY", "模型未就绪，检查 API key 是否配置");
        }
        checkQuota(cfg, LocalDate.now(ZONE).atStartOfDay(ZONE).toEpochSecond(), actor, actorCap);
    }

    // ---------------------------------------------------------------- 体检
    /**
     * 主线功能的自检。<b>只读、0 token、不写库</b>，可以随便打。
     *
     * <p><b>和 {@code /healthz} 的分工</b>：那个回答"进程还活着吗"，把它当探针用；
     * 这个回答"主线现在能不能干活、不能的话卡在哪一步"。两者会给出不同结论，这是正常的 ——
     * key 过期时进程百分之百健康，而主线一条都生成不出来。合成一个端点只会让容器
     * 因为一个过期的 key 反复重启，重启治不好 key。
     *
     * <p><b>为什么不在这里真调一次模型</b>：那样每被监控打一次就花一万 token，
     * 体检本身成了账单的主要来源。所以"模型可用"只能报 {@code ready}
     * （key 存在即 true），真正的连通性由第一次真实调用暴露，失败原因写进 markFailed。
     *
     * <p>所有数字口径都和扣费对得上：{@code today} 数的是 run 行，{@code quota.used}
     * 与 {@code checkQuota} 数的是同一个 {@code succeeded}。反过来，如果这里用另一套口径，
     * 迟早会出现"体检说还剩 90 次，点下去却说没额度"。
     */
    public Health health() {
        AppProps.Agent cfg = props.getAgent();
        // 窗口对齐自然日，与 ask/precheck 完全一致：跨零点时这里先归零，
        // 而不是等到下一次生成才发现"今天的账怎么还是昨天的"
        long dayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toEpochSecond();
        AgentRunStore.Stats s = runs.statsSince(dayStart);

        long runningAge = s.runningSince() == 0 ? 0 : Math.max(0, now() - s.runningSince());
        // running > 0 本身不是问题 —— 可能就是此刻正在跑。只有超过 stale-seconds
        // 才叫僵尸：那个值必须明显大于 timeout-ms，否则"模型慢"会被误判成"死了"
        long stuck = runningAge > cfg.getStaleSeconds() ? s.running() : 0;

        List<String> problems = new ArrayList<>();
        if (!cfg.isEnabled()) {
            problems.add("主线功能已关闭（app.agent.enabled=false）");
        }
        if (!catalog.ready()) {
            problems.add("模板加载失败：" + catalog.loadError());
        } else if (catalog.all().isEmpty()) {
            problems.add("模板文件里一个模板都没有");
        }
        if (!llm.ready()) {
            problems.add("模型未就绪：app.agent.api-key 与 analysis.api-key 都没有可用 key");
        }

        List<String> ids = catalog.all().stream().map(AgentTemplate::id).toList();
        long used = s.succeeded();
        return new Health(
                problems.isEmpty() ? "UP" : "DEGRADED",
                cfg.isEnabled(),
                new HealthTemplates(ids.size(), ids, catalog.loadError()),
                new HealthLlm(llm.ready(), llm.modelName(), cfg.getTimeoutMs()),
                new HealthToday(used, s.failed(), s.rejected(), s.tokensIn(), s.tokensOut()),
                new HealthQuota(cfg.getDailyCap(), cfg.getGuestDailyCap(), used,
                        Math.max(0, cfg.getDailyCap() - used)),
                new HealthRunning(s.running(), runningAge, cfg.getStaleSeconds(), stuck),
                problems);
    }

    /**
     * 体检结果，字段与 {@code GET /api/agent/health} 的响应体一一对应。
     *
     * @param status    UP / DEGRADED；DOWN 由 controller 在库都读不出来时给（那时连这个对象都构造不出）
     * @param problems  人能直接读的原因列表；空数组即 UP。不写成一句话，
     *                   因为"关掉了 + key 也没配"这种叠加态拼成句子会漏掉一半
     */
    public record Health(String status, boolean enabled, HealthTemplates templates, HealthLlm llm,
                         HealthToday today, HealthQuota quota, HealthRunning running,
                         List<String> problems) {
    }

    /** @param error 加载失败的原因，正常为 null —— 保证"文件写错了"在这里一眼可见 */
    public record HealthTemplates(int count, List<String> ids, String error) {
    }

    /** @param model 不保证可用，只保证配的是哪个 —— 排查"线上到底跑的哪个模型"要看它 */
    public record HealthLlm(boolean ready, String model, int timeoutMs) {
    }

    /** 今日全站计数。失败与拒答分开记：前者是花了钱没成，后者是一分没花。 */
    public record HealthToday(long succeeded, long failed, long rejected,
                              long tokensIn, long tokensOut) {
    }

    /**
     * @param used      今日已成功生成次数，全站口径（daily-cap 本来就是全站钱包保险丝）
     * @param remaining 剩余次数，不会为负 —— 负数出现在界面上只会引发"还能欠着用吗"的疑问
     */
    public record HealthQuota(int dailyCap, int guestDailyCap, long used, long remaining) {
    }

    /** @param stuck 超龄的 running 行数；>0 说明有实例死在半路，需要人工看 */
    public record HealthRunning(long count, long oldestAgeSec, int staleSeconds, long stuck) {
    }

    /** 一次报价要带的东西。字段与 {@code GET /api/agent/precheck} 的响应体一一对应。 */
    public record Precheck(
            String templateId,
            String title,
            boolean cacheable,
            String window,
            /** full / delta / cached / rejected */
            String strategy,
            /** 机器可读的判定依据，排查用；界面只读 note 与 detail */
            String reason,
            /** 模型是否就绪。false 时能看已有结论，但这次点下去必然失败 —— 按钮要置灰 */
            boolean ready,
            /** full 之后新增的候选条数 */
            int newCount,
            int candidates,
            /** 这次点下去预计消耗的输入 token；cached 与 rejected 为 0 */
            int estimateTokens,
            /** 同一批数据走全量要多少，用来让"省了多少"可验证 */
            int baselineTokens,
            long watermark,
            /** 已有结论的生成时间，没有则为 0 */
            long lastRunAt,
            /** 缓存命中时直接可读的 runId，界面不必再让用户点一次 */
            String runId,
            /** 当日已有的 full（full/delta 时非 null）：界面先把它当"当前版本"显示，按钮才是"更新" */
            String baseRunId,
            int remaining,
            String note,
            String detail,
            /**
             * 屏上那份结论是怎么来的。
             *
             * <p><b>必须是「已有结论的过程」，不是「这次将要做什么」的预览</b>：
             * full/delta 时浮层显示的是 baseRunId 那一版，若把本次 scan 的计数摆在它下面，
             * 用户会把「打包 48 条」读成那段结论的输入，而那段结论其实来自 69 条。
             * 本次的预期消耗已经由 {@code estimateTokens} 表达，不需要再借过程区说一遍。
             *
             * <p>没有可展示的结论时（今天第一次生成）为空列表，前端不渲染这一块。
             */
            List<Step> steps) {
    }

    // ---------------------------------------------------------------- 前置步骤

    /**
     * 三处共用的可用性检查：开关 → 模板目录 → 模板存在。
     *
     * <p>顺序有讲究：目录加载失败时 {@code find} 必然返回空，先报"未知模板"会把
     * "提示词文件写错了"误导成"你请求的 id 不对"。
     */
    private AgentTemplate available(String templateId, AppProps.Agent cfg) {
        if (!cfg.isEnabled()) {
            throw new AgentException(503, "DISABLED", "主线生成已关闭（app.agent.enabled=false）");
        }
        if (!catalog.ready()) {
            throw new AgentException(503, "NO_TEMPLATES",
                    "模板加载失败，主线功能不可用：" + catalog.loadError());
        }
        return catalog.find(templateId).orElseThrow(() -> new AgentException(400, "NO_TEMPLATE",
                "未知模板：" + templateId + "，可用：" + catalog.all().stream()
                        .map(AgentTemplate::id).collect(Collectors.joining(", "))));
    }

    /**
     * 取数 → 分类合并 → 打包 → 水位线。全免费，0 token。
     *
     * <p>抽出来是为了让 {@link #ask} 与 {@link #precheck} 走**同一条**取数口径。
     * 报价比实际执行多算或少算几条候选，用户看到的"新增 12 条"就是假的。
     */
    private Prepared scan(AgentTemplate t, String keywords) {
        LocalDate today = LocalDate.now(ZONE);
        long dayStart = today.atStartOfDay(ZONE).toEpochSecond();
        long to = today.plusDays(1).atStartOfDay(ZONE).toEpochSecond();
        long from = dayStart - (t.windowSeconds() - DAY);
        String windowKey = today.toString();
        // 检索用原样（大小写可能有意义），但缓存键只认内容：见 normalizeKeyword 的注释
        String params = paramsJson(t, normalizeKeyword(keywords));

        // 模板自带的同义词 + 调用方追加的关键词，OR 关系。
        // 这一步曾经漏过：t.keywords() 解析出来了却没人用，a-share-drop 实际喂给模型的是
        // 当天全部 139 条 —— 模板里"只分析下跌"的窗口从第一步就不存在。
        List<String> keywordsAll = new ArrayList<>(t.keywords());
        if (keywords != null && !keywords.isBlank()) {
            keywordsAll.add(keywords.trim());
        }

        List<NewsRow> rows = retrieval.fetch(from, to, keywordsAll);
        // classify 只调一次：调两次除了浪费，还可能在缓存未命中时给出不一致的结果
        Classifier.Result cls = Classifier.classify(rows);
        PackResult pack = Packer.withClassify(
                Packer.pack(cls.items(), Packer.MAX_L1, props.getAgent().getMaxTokensIn()), cls);
        long watermark = retrieval.watermark();
        log.info("主线候选 template={} window={} keywords={} {}", t.id(), t.window(),
                keywordsAll, pack.summary());
        return new Prepared(dayStart, from, to, windowKey, params, pack, watermark);
    }

    /** §10.2 第一步：跨策略比对。full 与 delta 的输出形态相同，都算缓存候选。 */
    private Optional<AgentRunStore.RunRow> findHit(AgentTemplate t, Prepared in) {
        return t.cacheable()
                ? runs.findSucceeded(t.id(), in.windowKey(), in.watermark(), in.params())
                : Optional.empty();
    }

    /** 报价用：全量提示词的真实长度换算成 token。 */
    private static int fullTokens(AgentTemplate t, Prepared in) {
        return PromptBuilder.tokens(PromptBuilder.render(t, in.pack(), in.from(), in.to()).length())
                + Packer.PROMPT_OVERHEAD;
    }

    /** 还剩几次。全站额度与账号额度取更紧的那个，和 {@link #checkQuota} 的判定口径一致。 */
    private int remaining(AppProps.Agent cfg, long dayStart, String actor, Integer actorCap) {
        int left = cfg.getDailyCap() - (int) runs.countSucceededSince(dayStart, null);
        if (actorCap != null && actor != null) {
            left = Math.min(left, actorCap - (int) runs.countSucceededSince(dayStart, actor));
        }
        return Math.max(0, left);
    }

    /**
     * note 的语气，决定浮层用灰字还是琥珀。
     *
     * <p>只有一种情况要显形：{@code NOTE_ROLLBACK} —— 那句"已回退上一版"意味着
     * 用户眼前是旧结论，纯灰会被当成装饰跳过。其余几句（阈值不到、走全量）
     * 说的都是"这次怎么花"，不涉及内容新旧，灰字足够。
     *
     * <p>为什么在服务端映射而不是前端按文案匹配：文案会改，改了前端就静默失效，
     * 而这种失效没有任何报错。映射放在常量旁边，改文案的人必然看到它。
     */
    public static String noteKind(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return NOTE_ROLLBACK.equals(note) ? "stale" : "info";
    }

    /** {@link #scan} 的产物。字段都是后续判定与渲染要用的，不含任何结论。 */
    private record Prepared(
            long dayStart,
            long from,
            long to,
            String windowKey,
            String params,
            PackResult pack,
            long watermark) {
    }

    /**
     * 三档判定的准备：查 base、算新增、数今天的增量次数。
     *
     * <p>全部是数据库查询与整数运算，0 token。放在 {@link SingleFlight} 之外，
     * 是因为第二个并发请求拿到 leader 的结果就结束了，没必要把这些再跑一遍。
     */
    private GenCtx prepare(AgentTemplate t, PackResult pack, String params,
                           String windowKey, long watermark, long from, long to) {
        if (!t.cacheable()) {
            // brief: false 的模板每次都必须新生成，不进缓存也不参与增量
            return new GenCtx(Strategy.Mode.FULL, NOTE_NOT_CACHEABLE,
                    null, null, 0, null, 0, 0, 0, null);
        }

        Optional<AgentRunStore.RunRow> baseRow = runs.findBaseFull(t.id(), windowKey, params);
        Strategy.Base base = baseRow
                .map(r -> new Strategy.Base(r.id(), r.watermark(), r.windowKey()))
                .orElse(null);

        int added = 0;
        if (base != null) {
            for (PackedNews p : pack.all()) {
                if (p.id() > base.watermark()) {
                    added++;
                }
            }
        }
        int deltaToday = base == null ? 0 : runs.countDeltaToday(t.id(), windowKey, params);

        Strategy.Decision d = Strategy.decide(
                Strategy.Input.of(windowKey, watermark, base, added, deltaToday));
        // 判定细节走日志，note 只留一句给用户看的话（≤18 字，见 MAX_NOTE_WIDTH）。
        // 两者分开是 S1.4 定的：note 要进浮层，塞不下"阈值 65、今天第 3 次"这些过程量。
        log.info("主线判定 template={} mode={} reason={} added={} deltaToday={} {}",
                t.id(), d.mode(), d.reason(), added, deltaToday, d.note());

        if (d.mode() == Strategy.Mode.CACHE) {
            AgentRunStore.RunRow row = baseRow.orElseThrow(() -> new AgentException(500, "STORE_ERROR",
                    "判定为缓存但查不到 base full，数据不一致"));
            return new GenCtx(Strategy.Mode.CACHE, d.note(), row, null, 0, null, 0, 0, added, null);
        }
        if (d.mode() == Strategy.Mode.FULL) {
            return new GenCtx(Strategy.Mode.FULL, d.note(), null, null, 0, null, 0, 0, added, null);
        }

        // DELTA：base 的结论读不出来就没法增量。降级成全量而不是报错 ——
        // 用户点的是"生成"，不是"验证我的历史记录完不完整"
        AgentRunStore.RunRow row = baseRow.orElseThrow(() -> new AgentException(500, "STORE_ERROR",
                "判定为增量但查不到 base full，数据不一致"));
        Answer prev = readAnswer(row);
        if (prev == null || prev.empty()) {
            log.warn("上一版结论无法解析，本次降级全量 base={}", row.id());
            return new GenCtx(Strategy.Mode.FULL, NOTE_NO_PREV,
                    null, null, 0, null, 0, 0, added, null);
        }
        PromptBuilder.DeltaPrompt dp = PromptBuilder.renderDelta(t, pack, base.watermark(), prev,
                row.createdAt(), row.candidates(), from, to, props.getAgent().getMaxTokensIn());

        // 第二个闸：算出来的增量比全量还长，就不该走增量。
        //
        // 光看新增条数不够。2026-11-28 实测：当天 60 条候选，full 3542 token、delta 3770，
        // 增量反而贵 6%。原因是总量没到 L1 上限（80 条）时，全量里每条都有正文；
        // 而增量要额外付 57 条旧标题 + 14 条证据正文，省下的那 3 条新增正文抵不过。
        // 所以判据必须是**两种提示词的真实长度**，不是条数阈值 —— 渲染两遍只要几毫秒，
        // 比错发一次请求便宜三个数量级。
        int fullChars = PromptBuilder.render(t, pack, from, to).length();
        if (dp.chars() >= fullChars) {
            log.info("增量不比全量省，改走全量 template={} delta={}chars full={}chars",
                    t.id(), dp.chars(), fullChars);
            return new GenCtx(Strategy.Mode.FULL, NOTE_COST_GATE,
                    null, null, 0, null, 0, 0, added, null);
        }

        // 第三个参数是 baseRow：CACHE 靠它直接返回，DELTA 靠它读 previous，
        // 以及 suspect 护栏回退时把上一版交给用户。漏传就会在回退那一刻 NPE
        return new GenCtx(Strategy.Mode.DELTA, d.note(), row, row.id(), row.watermark(),
                prev, row.createdAt(), row.candidates(), added, dp);
    }

    /**
     * 真正生成一次。只有抢到生成权时才会执行 —— 调用它之前必须已通过配额、缓存、guard 与判定。
     *
     * <p>拆成独立方法的原因：它要被 {@link SingleFlight} 包住，而抢锁之前的步骤
     * （取数、分类合并、打包、缓存比对、拒答判定）不该被第二个请求重跑一遍。
     */
    private Result generate(AgentTemplate t, PackResult pack, String params, String windowKey,
                            String actor, long watermark, long from, long to, GenCtx ctx,
                            Proc proc) {
        String runId = newRunId();
        boolean delta = ctx.mode() == Strategy.Mode.DELTA;
        String strategy = delta ? "delta" : "full";

        // 过程从「取数」开始（scan 之后就已经进 proc 了），走到哪一步就在哪一步补一行。
        // 为什么在入口就建好而不是各分支各拼一份：失败路径也要留下已经走过的步骤 ——
        // 「卡在解析」和「卡在模型超时」在界面上都只是一句 502，靠这几行才分得开。
        proc.add(Step.of("guard", "材料校验", "通过"));
        if (delta) {
            proc.add(Step.of("delta", "增量基线", "新增 " + ctx.added() + " 条"));
        }

        // 方案 A：在数据库抢一次生成权。这一步是"还没花钱就能拦住"的唯一位置。
        // strategy 与 base_run_id 不参与唯一索引（见 AgentRunStore.claim 的注释），
        // 所以同一天同一批数据无论走哪档，都只可能产出一份结论。
        long staleBefore = now() - props.getAgent().getStaleSeconds();
        AgentRunStore.Claim claim = runs.claim(runId, t.id(), params, actorOr(actor), windowKey,
                strategy, ctx.baseRunId(), watermark, staleBefore);

        if (claim.kind() == AgentRunStore.Kind.DONE) {
            // 抢的那一瞬间别人刚好写完了。钱没花，直接给结果
            log.info("主线抢占时已有结果 template={} watermark={} run={}",
                    t.id(), watermark, claim.row().id());
            return toResult(claim.row(), true, null);
        }
        if (claim.kind() == AgentRunStore.Kind.BUSY) {
            // 同进程的并发已经被 SingleFlight 合并，走到这里只可能是另一个实例在跑
            return waitForPeer(t, params, windowKey, watermark);
        }

        String user;
        if (delta) {
            // 直接复用判定阶段渲染好的那份：再渲染一遍除了浪费几毫秒，
            // 还会让"判定时算的长度"和"真正发出去的长度"存在第二个来源
            PromptBuilder.DeltaPrompt dp = ctx.deltaPrompt();
            user = dp.user();
            log.info("主线增量提示词 run={} base={} added={} evidence={}{} chars={}",
                    runId, ctx.baseRunId(), ctx.added(), dp.evidenceCount(),
                    dp.trimmed() ? "（证据已裁剪）" : "", dp.chars());
            runs.insertStep(runId, 1, "delta", dp.chars(), null, 0, 0, 0,
                    "base=" + ctx.baseRunId() + " added=" + ctx.added() + " evidence=" + dp.evidenceCount());
        } else {
            user = PromptBuilder.render(t, pack, from, to);
        }

        // 先占一行「等待中」再发请求。这一行是流式体验的关键：前面五步加起来不到 100ms，
        // 接下来这十几秒里如果界面上什么都不动，用户会以为卡死了 —— 而它确实在等，
        // 只是等的过程中没有任何可报的数字（token 要回来才知道）。
        proc.add(Steps.llmWaiting());

        AgentLlm.Reply reply;
        long started = System.currentTimeMillis();
        try {
            reply = llm.chat(t.system(), user);
        } catch (LlmCallException e) {
            // 拿到了 usage 的失败：token 必须如实记。这类失败（截断、空正文）是 HTTP 200，
            // 钱已经花出去了，记 0 会让账单和库里的账永远对不上
            long ms = System.currentTimeMillis() - started;
            runs.markFailed(runId, e.getMessage(), e.tokensIn(), e.tokensOut(), ms);
            proc.replace(Steps.llmFailed(ms, "已消耗 " + (e.tokensIn() + e.tokensOut()) + " token"));
            runs.stampSteps(runId, json(proc.list()));
            log.warn("主线生成失败（已消耗 {} token）run={} template={}：{}",
                    e.tokensIn() + e.tokensOut(), runId, t.id(), e.getMessage());
            throw new AgentException(502, "MODEL_ERROR", userModelError(e.getMessage()));
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - started;
            // 拿不到 usage 的失败（超时、401、网络）：记 0，不编造。
            // 这里有两句话要分开：给用户的是短人话（详情只会让人来问"坏了？"），
            // 给运维的是原始原因 —— 它进日志，不进浮层。
            String detail = String.valueOf(e.getMessage());
            runs.markFailed(runId, detail, 0, 0, ms);
            proc.replace(Steps.llmFailed(ms, userModelError(detail)));
            runs.stampSteps(runId, json(proc.list()));
            log.warn("主线生成失败 run={} template={}：{}", runId, t.id(), detail);
            throw new AgentException(502, "MODEL_ERROR", userModelError(detail));
        }

        proc.replace(Steps.llm(reply.latencyMs(), reply.tokensIn()));
        runs.insertStep(runId, 2, "llm", user.length(), reply.content(),
                reply.tokensIn(), reply.tokensOut(), reply.latencyMs(), null);

        AnswerParser.Result parsed;
        try {
            parsed = parser.parse(reply.content(), allowedIds(pack, ctx), t.title());
        } catch (Exception e) {
            runs.markFailed(runId, "输出解析失败：" + e.getMessage(),
                    reply.tokensIn(), reply.tokensOut(), reply.latencyMs());
            runs.stampSteps(runId, json(proc.list()));
            throw new AgentException(502, "BAD_OUTPUT", e.getMessage());
        }

        proc.add(Steps.cite(parsed));

        if (parsed.empty()) {
            // 钱已经花了，但结果是废的。判 rejected 而不是 succeeded：
            // 否则下一次点同一水位线会命中这条"没有结论的成功记录"，永远拿不到结果。
            String detail = "模型给不出有引用支撑的结论（" + parsed.note() + "），已丢弃。可稍后重试。";
            runs.stampSteps(runId, json(proc.list()));
            runs.markRejected(runId, "empty-answer：" + detail, pack.candidates());
            log.warn("主线全部结论无有效引用 run={} {}", runId, parsed.note());
            return new Result(runId, t.id(), "rejected", strategy, t.title(), null,
                    "empty-answer", detail, null, watermark, pack.candidates(),
                    reply.tokensIn(), reply.tokensOut(), now(), false, reply.model(), proc.list());
        }

        // 增量的特有护栏：输出条数不到上一版的一半，且新增本来就不多，
        // 更像模型"把旧主线全删了重写"而不是"今天真的只剩一个主题"。
        //
        // 判据是**相对上一版骤减**，不是绝对条数。第一版写成"输出 ≤1 条"，结果测试里
        // base 本来就只有 1 条，增量刚跑完就被自己的护栏打回 —— 绝对阈值在基线不同时
        // 全是假阳性。相对判据的含义很清楚：比上一版少一半以上，才叫"删减"。
        // 也不用模型自报的 dropped：自述不可验证，条数可验证。
        // 嵌套而不是把取条数提到外面：全量时 baseAnswer 是 null，提出来就是空指针
        if (delta && ctx.added() < 5) {
            int baseBullets = ctx.baseAnswer().bullets().size();
            int outBullets = parsed.answer().bullets().size();
            // 第二个判据比条数更准：引用的 id 全部落在新增条目上，说明模型压根没看旧的。
            // 2026-11-28 真模型验收就是这种情况 —— 上一版 5 条主线，增量输出 3 条，
            // 3*2 >= 5 躲过了条数判据，但三条引用全是新插进来的 1247/1248/1249。
            boolean onlyNew = parsed.answer().allRefs().stream().allMatch(id -> id > ctx.baseWatermark());
            if (outBullets * 2 < baseBullets || (onlyNew && baseBullets >= 3)) {
                String detail = onlyNew
                        ? "增量结果全部只引用新增条目（" + outBullets + " 条主线，上一版 " + baseBullets
                          + " 条），没有覆盖全天，已回退到上一版。"
                        : "增量结果只剩 " + outBullets + " 条主线（上一版 " + baseBullets
                          + " 条，新增 " + ctx.added() + " 条），疑似模型过度删减，已回退到上一版。";
                // 用 failed 而不是 rejected：token 已经花了，必须如实记账；
                // 而 rejected 会把 tokens 强制写成 0，账就和账单对不上了。
                runs.stampSteps(runId, json(proc.list()));
                runs.markFailed(runId, "suspect-delta：" + detail,
                        reply.tokensIn(), reply.tokensOut(), reply.latencyMs());
                log.warn("主线增量疑似过度删减，回退上一版 run={} base={} bullets={} added={}",
                        runId, ctx.baseRunId(), outBullets, ctx.added());
                // 给用户的那句是短的；带条数的长版本已经写进 markFailed 的 error 列，
                // 排查时按 run 去查就行。把长句直接塞进 note 的后果见 S1.4 原型：
                // 44 个字在浮层里折成三行，把整块标题区顶变形。
                return toResult(ctx.baseRow(), true, NOTE_ROLLBACK);
            }
        }

        // 先记过程再定终态：过程是「做过什么」这个事实，状态是「成了没有」这个结论。
        // 反过来的话，markSucceeded 与 stampSteps 之间被杀，会留下一条「成功但没有过程」的行
        runs.stampSteps(runId, json(proc.list()));
        boolean stored = runs.markSucceeded(runId, json(parsed.answer()), json(parsed.refs()),
                pack.candidates(), reply.tokensIn(), reply.tokensOut(), reply.model(), reply.latencyMs());
        if (!stored) {
            // claim 之后唯一的竞争窗口：本进程生成期间，另一个进程也用同一把 key 跑完了
            // （它的 running 行与这次的互斥，正常抢不到；只有它在本次释放前就完成才会出现）。
            // 这次的钱白花，但结果不能覆盖赢家的。
            runs.markFailed(runId, "同键已有成功结果，本次作废（并发）", reply.tokensIn(),
                    reply.tokensOut(), reply.latencyMs());
            log.warn("主线并发重复消费 run={} watermark={} tokens={}+{}（钱已花）",
                    runId, watermark, reply.tokensIn(), reply.tokensOut());
            AgentRunStore.RunRow winner = runs.findSucceeded(t.id(), windowKey, watermark, params)
                    .orElseThrow(() -> new AgentException(500, "STORE_ERROR",
                            "唯一索引冲突但查不到成功记录，数据不一致"));
            return toResult(winner, true, null);
        }

        log.info("主线生成成功 run={} template={} strategy={} candidates={} tokens={}+{} latency={}ms {}",
                runId, t.id(), strategy, pack.candidates(), reply.tokensIn(), reply.tokensOut(),
                reply.latencyMs(), parsed.note());
        return new Result(runId, t.id(), "succeeded", strategy, parsed.answer().title(),
                parsed.answer(), null, null, ctx.note(), watermark, pack.candidates(),
                reply.tokensIn(), reply.tokensOut(), now(), false, reply.model(), proc.list());
    }

    /**
     * 把模型失败翻译成给用户看的一句话。
     *
     * <p>这里有两类读者，必须分开：浮层里的读者是同事，看到「API key 无效（401）：
     * 检查 app.agent.api-key / DEEPSEEK_API_KEY / ~/.codex/auth.json」只会来问一句"坏了？"，
     * 然后你才发现是 key 的问题 —— 这一来一回什么也没解决。所以给用户的只有人话，
     * 原始原因留在 {@code log.warn} 里，运维去日志里翻。
     *
     * <p>约束和 {@code AnalysisCopyTest} 一致：用户可见文案 ≤14 字、不含配置项名。
     * 只是这里不单独写成一个测试类，因为错误分类是字符串匹配，逐条断言收益低、
     * 反而把「哪种措辞算人话」这个真问题淹没在表驱动里。
     */
    private static String userModelError(String detail) {
        if (detail == null || detail.isBlank()) {
            return "模型调用失败，稍后再试";
        }
        if (detail.contains("超时") || detail.contains("timed out") || detail.contains("Timeout")) {
            return "模型响应超时，稍后再试";
        }
        if (detail.contains("401") || detail.contains("无效") || detail.contains("key")) {
            // 认证类问题也归类到「服务异常」：用户知道了是 key 错也无能为力，
            // 反而会追问「那 key 是什么」。这类细节只该出现在日志里。
            return "模型服务异常，稍后再试";
        }
        return "模型调用失败，稍后再试";
    }

    /**
     * 另一个实例正在生成时，等它写完再返回。
     *
     * <p>为什么不用 409 直接打回去：用户点"生成"想要的是结果，不是"再点一次"。
     * 代价是占着一个请求线程，所以设了等待上限（{@code busy-wait-ms}）。
     */
    private Result waitForPeer(AgentTemplate t, String params, String windowKey, long watermark) {
        int waitMs = props.getAgent().getBusyWaitMs();
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException(503, "INTERRUPTED", "等待生成结果时被中断");
            }
            Optional<AgentRunStore.RunRow> done = runs.findSucceeded(t.id(), windowKey, watermark, params);
            if (done.isPresent()) {
                log.info("跨实例等待拿到结果 template={} watermark={} run={}",
                        t.id(), watermark, done.get().id());
                return toResult(done.get(), true, null);
            }
        }
        throw new AgentException(503, "BUSY",
                "同一批数据正在另一个实例上生成，等了 " + waitMs + " 毫秒还没好，稍后再看");
    }

    /** 把"别人算的"结果标成缓存命中。rejected 不改：它本来就没花钱，也不该显示成缓存。 */
    private static Result markCached(Result r) {
        if (!"succeeded".equals(r.status())) {
            return r;
        }
        return new Result(r.runId(), r.templateId(), r.status(), r.strategy(), r.title(), r.answer(),
                r.reason(), r.detail(), r.note(), r.watermark(), r.candidates(), r.tokensIn(),
                r.tokensOut(), r.generatedAt(), true, r.model(), r.steps());
    }

    /** 只读查询，给轮询 / 重连用。S1.4 的接口会用到。 */
    public Optional<AgentRunStore.RunRow> find(String runId) {
        return runs.findById(runId);
    }

    /**
     * 按 runId 还原一次生成的结果，形态与 {@link #ask} 的返回值一致。
     *
     * <p>为什么不让接口层自己解析 {@code answer_json}：那是库里的内部格式，
     * 解析失败该降级成"无结论"还是报错，只有编排层知道（{@link #readAnswer} 已经定了）。
     * 两处各写一遍 JSON 解析，迟早在"脏数据怎么办"上分叉。
     *
     * <p>{@code cached=true}：读的是已经付过费的结论，不该让界面显示"刚刚生成"。
     */
    public Optional<Result> snapshot(String runId) {
        return runs.findById(runId).map(row -> {
            if ("succeeded".equals(row.status())) {
                return toResult(row, true, null);
            }
            // failed / running / rejected：没有可展示的结论，但状态要给出去，
            // 否则轮询方只能看到 404，分不清"还在跑"和"这次没成"
            return new Result(row.id(), row.templateId(), row.status(), row.strategy(), null, null,
                    null, null, null, row.watermark(), row.candidates(), row.tokensIn(),
                    row.tokensOut(), row.createdAt(), true, row.model(), readSteps(row));
        });
    }

    // ---------------------------------------------------------------- 内部

    private void checkQuota(AppProps.Agent cfg, long dayStart, String actor, Integer actorCap) {
        long total = runs.countSucceededSince(dayStart, null);
        if (total >= cfg.getDailyCap()) {
            throw new AgentException(429, "CAPPED",
                    "全站今日主线已生成 " + total + " 次（上限 " + cfg.getDailyCap() + "），明天再试");
        }
        // 角色额度只判"这个账号成功了几次"：失败与拒答都不扣，和 §10.7 的扣减规则一致
        if (actorCap != null && actor != null && runs.countSucceededSince(dayStart, actor) >= actorCap) {
            throw new AgentException(429, "CAPPED",
                    "本账号今日 " + actorCap + " 次主线已用完，明天再试");
        }
    }

    /**
     * 引用白名单。
     *
     * <p>增量时要把<b>旧结论引用过的 id</b> 也算进去：{@code <news>} 只给新增条目，
     * 但模型能从 {@code <previous>} 段看到旧引用的 {@code [[id]]}，它照着写是合法的。
     * 只放本次候选的话，这些引用会被判越界剥掉，正确的结论反而被扔掉。
     */
    private Set<Long> allowedIds(PackResult pack, GenCtx ctx) {
        Set<Long> ids = pack.all().stream().map(PackedNews::id).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ctx.baseAnswer() != null) {
            ids.addAll(ctx.baseAnswer().allRefs());
        }
        return ids;
    }

    private Result toResult(AgentRunStore.RunRow row, boolean cached, String note) {
        Answer answer = readAnswer(row);
        return new Result(row.id(), row.templateId(), "succeeded", row.strategy(),
                answer == null ? null : answer.title(), answer, null, null, note,
                row.watermark(), row.candidates(), row.tokensIn(), row.tokensOut(),
                row.createdAt(), cached, row.model(), readSteps(row));
    }

    /**
     * 读回「过程」。
     *
     * <p>解析失败降级成空列表而不是抛错：过程是解释性数据，它坏了不该让
     * 「已经付过费的结论」跟着打不开。日志留一条，能查到是哪个 run。
     */
    private List<Step> readSteps(AgentRunStore.RunRow row) {
        String json = row.stepsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<Step>>() {
            });
        } catch (Exception e) {
            log.warn("run {} 的 steps_json 无法解析：{}", row.id(), e.getMessage());
            return List.of();
        }
    }

    /** 库里存的是自己写的 JSON，理论上一定可解析；解析不出来说明代码有 bug，不该 500 掉整个请求。 */
    private Answer readAnswer(AgentRunStore.RunRow row) {
        String json = row.answerJson();
        if (json == null || json.isBlank()) {
            log.error("run {} 标记成功但没有 answer_json", row.id());
            return null;
        }
        try {
            return mapper.readValue(json, Answer.class);
        } catch (Exception e) {
            log.error("run {} 的 answer_json 无法解析：{}", row.id(), e.getMessage());
            return null;
        }
    }

    /**
     * 关键词归一化，只用于缓存键。
     *
     * <p>实测踩到：同一个人先打 {@code ai}、再打 {@code AI}，缓存键不同，于是两次全量各花 842 token
     * —— 而检索语句对 ASCII 本来就不区分大小写，两次拿到的材料完全一样。键必须只由"内容"决定，
     * 不能由输入时的大小写与前后的空格决定；否则每次手抖都要用户再付一次钱。
     *
     * <p>只归一化键，不改检索用的原值：中文与全角符号的处理留给 SQL 那边，
     * 在这里多做一层转换，反而可能让"用户打的关键词"和"工具查的关键词"对不上。
     */
    static String normalizeKeyword(String keywords) {
        if (keywords == null) return "";
        return keywords.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private String paramsJson(AgentTemplate t, String keywords) {        ObjectNode n = mapper.createObjectNode();
        n.put("templateId", t.id());
        n.put("window", t.window());
        if (keywords != null && !keywords.isBlank()) {
            n.put("keywords", keywords.trim());
        }
        return n.toString();
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new AgentException(500, "SERIALIZE_ERROR", "结果序列化失败：" + e.getMessage());
        }
    }

    private static String actorOr(String actor) {
        return actor == null || actor.isBlank() ? "unknown" : actor;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    /** {@code r_20261127T181530_a3f9}：可排序、可读、能直接贴到群里定位。 */
    private static String newRunId() {
        return "r_" + ID_STAMP.format(Instant.now()) + "_"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
    }

    /**
     * 判定结果 + 执行它需要的全部上下文。
     *
     * <p>做成一个不可变 record，是为了能在 {@link SingleFlight} 的 lambda 里安全捕获：
     * 判定过程中要读 base、读旧结论、可能把 DELTA 降级成 FULL，这些"改主意"必须发生在
     * 进 lambda 之前，否则并发下两个线程看到的是不同的 decision。
     *
     * @param mode           CACHE / FULL / DELTA
     * @param note           给用户看的一句话（"新增 2 条，未达重算阈值"）
     * @param baseRow        base 那一行：CACHE 时直接返回它，DELTA 时读它的结论做 previous，
     *                       护栏回退时也返回它。FULL 时为 null
     * @param baseRunId      delta 的 base；FULL / CACHE 为 null
     * @param baseWatermark  base 的水位线，切分新旧条目用
     * @param baseAnswer     base 的结论，delta 的 "previous" 段
     * @param baseCreatedAt  base 的生成时间
     * @param baseCandidates base 当时的候选条数
     * @param added          候选里比 base 新的条数
     * @param deltaPrompt    判定阶段就渲染好的增量提示词（含成本比对结果）；非 DELTA 为 null
     */
    private record GenCtx(
            Strategy.Mode mode,
            String note,
            AgentRunStore.RunRow baseRow,
            String baseRunId,
            long baseWatermark,
            Answer baseAnswer,
            long baseCreatedAt,
            int baseCandidates,
            int added,
            PromptBuilder.DeltaPrompt deltaPrompt) {
    }

    /**
     * 一次生成的结果，直接对应接口返回体（见文档 §9）。
     *
     * @param status  succeeded / rejected
     * @param strategy full / delta
     * @param cached  true = 命中已有结果，本次没有调用模型
     * @param reason  rejected 时的机器码：no-data / min-hits / off-topic / empty-answer
     * @param detail  rejected 时给用户看的一句话
     * @param note    非错误路径的提示，例如"新增 2 条，未达重算阈值（≥3），先看上一版"。
     *                S1.4 的浮层靠它告诉用户"为什么这次没重算"
     * @param generatedAt 生成时间，epoch 秒
     */
    public record Result(
            String runId,
            String templateId,
            String status,
            String strategy,
            String title,
            Answer answer,
            String reason,
            String detail,
            String note,
            long watermark,
            int candidates,
            int tokensIn,
            int tokensOut,
            long generatedAt,
            boolean cached,
            String model,
            /** 这次生成做了哪些确定性步骤（取数/剔除/打包/校验/模型/引用）。缓存命中时读的是库里那份；老数据为空 */
            List<Step> steps) {
    }
}
