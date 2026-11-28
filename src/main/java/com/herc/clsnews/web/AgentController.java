package com.herc.clsnews.web;

import com.herc.clsnews.agent.AgentCatalog;
import com.herc.clsnews.agent.AgentException;
import com.herc.clsnews.agent.AgentService;
import com.herc.clsnews.agent.AgentTemplate;
import com.herc.clsnews.agent.Answer;
import com.herc.clsnews.agent.Proc;
import com.herc.clsnews.config.AppProps;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主线（模板分析）的五个接口，见设计文档 §9。
 *
 * <p>读写分工：{@code templates} / {@code precheck} / {@code runs} / {@code health}
 * 都是免费且无副作用的查询，只有 {@code ask} 会真的调模型。这个划分直接决定了
 * 界面上"打开浮层不花钱"能不能成立 —— 浮层打开时打的是 precheck，
 * 它跑的是同一套取数与判定，只是不调模型。
 *
 * <p><b>为什么 precheck 与 ask 分成两个接口而不是"ask 带个 dryRun 参数"</b>：
 * dryRun 的语义是"假装执行一次"，很容易在某次改动里被漏掉一个分支，变成真的执行。
 * 两个路径各自有类型上的保证（一个返回不存在的 runId 也不可能落库），更适合当钱的门。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agent;
    private final AgentCatalog catalog;
    private final AppProps props;
    private final AgentStreams streams;

    public AgentController(AgentService agent, AgentCatalog catalog, AppProps props,
                           AgentStreams streams) {
        this.agent = agent;
        this.catalog = catalog;
        this.props = props;
        this.streams = streams;
    }

    /**
     * 可用的模板。浮层顶部的 chips 就是这个列表。
     *
     * <p>只下发界面需要的字段：{@code system}/{@code user} 是提示词正文，几百字一条，
     * 前端一个字都用不到，下发出去只是让人多点一次"查看源码"。
     */
    @GetMapping("/templates")
    public ResponseEntity<?> templates() {
        if (!catalog.ready()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "模板加载失败，主线功能不可用：" + catalog.loadError(),
                    "code", "NO_TEMPLATES"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (AgentTemplate t : catalog.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("title", t.title());
            // brief=false 的模板每次都是全量，界面上不该给它"刷新"的暗示
            m.put("cacheable", t.cacheable());
            m.put("window", t.window());
            m.put("minHits", t.minHits());
            m.put("costHint", t.costHint());
            m.put("keywords", t.keywords());
            items.add(m);
        }
        return ResponseEntity.ok(Map.of("items", items));
    }

    /**
     * 免费报价：走缓存、走增量还是走全量，大概花多少，还剩几次。
     *
     * <p>界面拿它在打开浮层时决定：有现成结论就直接显示（{@code runId}），
     * 没有就把按钮上的文案换成"用「降准」生成 · 约 6.2k"。整个请求 0 token。
     *
     * @param keywords 追加的检索关键词，空表示只用模板自带的
     */
    @GetMapping("/precheck")
    public ResponseEntity<?> precheck(@RequestParam(required = false) String templateId,
                                      @RequestParam(required = false) String keywords,
                                      HttpServletRequest req) {
        String role = Roles.of(req);
        try {
            return ResponseEntity.ok(agent.precheck(templateId, keywords, actorOf(req, role),
                    Roles.capOf(role, props)));
        } catch (AgentException e) {
            return error(e);
        }
    }

    /**
     * 真生成。同步等模型，前端超时要给到 2 分钟（默认 15 秒必然误判失败）。
     *
     * <p>请求体刻意做得宽松：{@code window} / {@code threadId} 在 Step 1 不生效，
     * 留着是为了让前端不必等接口长出来才开始拼请求体。
     *
     * @param body templateId 必填；keywords 可选；force=true 时越过缓存重新生成（付费）
     */
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody(required = false) AskBody body, HttpServletRequest req) {
        String role = Roles.of(req);
        String templateId = body == null ? null : body.templateId();
        String keywords = body == null ? null : body.keywords();
        boolean force = body != null && Boolean.TRUE.equals(body.force());
        try {
            AgentService.Result r = agent.ask(templateId, keywords, force, actorOf(req, role),
                    Roles.capOf(role, props));
            return ResponseEntity.ok(view(r));
        } catch (AgentException e) {
            return error(e);
        }
    }

    /**
     * 真生成，但过程是一行一行推出来的（SSE）。
     *
     * <p><b>为什么需要第二条生成接口，而不是把 {@link #ask} 改成流式</b>：小程序里
     * 拿 chunk 的 API 和 H5 完全是两套（{@code wx.request enableChunked} vs
     * {@code fetch + ReadableStream}），而老版本、curl、脚本、以及已经发出去的客户端
     * 都还在用一次性返回。两条并行，各按各自的能力走，成本远低于强行统一。
     *
     * <p><b>事件只有四种</b>：
     * <ul>
     *   <li>{@code open} —— 流已建立，生成开始。前端据此把按钮切成"生成中"</li>
     *   <li>{@code step} —— 走完一步。data 就是一个 {@code Step}</li>
     *   <li>{@code done} —— 终态，data 与 {@link #ask} 的响应体<b>完全同构</b>，
     *       所以缓存命中、拒答、失败这几种收尾不需要前端写第二套解析</li>
     *   <li>{@code error} —— 生成没能开始或中途炸了。{@code code} 与同步接口一致</li>
     * </ul>
     *
     * <p><b>为什么没有 tick 事件</b>：前端自己有时钟。从按下按钮到收到 {@code done}，
     * 中间有几十秒，服务端每一秒推一次"还在"除了浪费流量没有任何信息量 ——
     * 而"已等待 8.4 秒"这个数字，浏览器算得比服务端推得更准。
     *
     * <p><b>开流前的四道门走同步返回</b>：没开、没模板、没 key、没额度，这四种情况
     * 必须还是 4xx / 5xx，不能变成"200 + 一个 error 事件"。见
     * {@link AgentService#preflight}。取数放在开流之后，因为那几行正是要推给用户看的东西。
     */
    @PostMapping("/ask/stream")
    public Object askStream(@RequestBody(required = false) AskBody body, HttpServletRequest req) {
        String role = Roles.of(req);
        String templateId = body == null ? null : body.templateId();
        String keywords = body == null ? null : body.keywords();
        boolean force = body != null && Boolean.TRUE.equals(body.force());
        String actor = actorOf(req, role);
        Integer cap = Roles.capOf(role, props);

        try {
            agent.preflight(templateId, actor, cap);
        } catch (AgentException e) {
            // 此时还没开流，状态码还能如实给出去
            return error(e);
        }

        // 比模型超时多 20 秒：模型回来后还有解析、校验、落库。
        // 给少了会出现"模型其实成功了、前端却先一步超时断开"，而那次的钱已经花了。
        SseEmitter emitter = new SseEmitter(props.getAgent().getTimeoutMs() + 20_000L);
        // 断开之后不再往里写：send 抛一次就够了，之后每一次都是在往死连接上撞
        AtomicBoolean closed = new AtomicBoolean(false);

        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(t -> closed.set(true));
        emitter.onCompletion(() -> closed.set(true));

        ScheduledFuture<?> beat = streams.ping(
                () -> push(emitter, closed, SseEmitter.event().comment("ping")));

        streams.submit(() -> {
            try {
                push(emitter, closed, SseEmitter.event().name("open")
                        .data(Map.of("at", System.currentTimeMillis()), MediaType.APPLICATION_JSON));

                Proc.Sink sink = step -> push(emitter, closed, SseEmitter.event().name("step")
                        .data(step, MediaType.APPLICATION_JSON));

                AgentService.Result r = agent.ask(templateId, keywords, force, actor, cap, sink);
                push(emitter, closed, SseEmitter.event().name("done")
                        .data(view(r), MediaType.APPLICATION_JSON));
            } catch (AgentException e) {
                log.info("主线流式生成中止 template={} code={}：{}", templateId, e.code(), e.getMessage());
                push(emitter, closed, SseEmitter.event().name("error").data(
                        Map.of("code", e.code(), "error", String.valueOf(e.getMessage())),
                        MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 走到这里说明有未预料的异常。仍然要发 error 事件 ——
                // 直接让流断掉的话，前端只能看到"连接结束但没有结果"，报不出任何有用的原因
                log.error("主线流式生成异常 template={}", templateId, e);
                push(emitter, closed, SseEmitter.event().name("error").data(
                        Map.of("code", "INTERNAL", "error", String.valueOf(e.getMessage())),
                        MediaType.APPLICATION_JSON));
            } finally {
                beat.cancel(false);
                closed.set(true);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 已经因为客户端断开而结束过了
                }
            }
        });

        return emitter;
    }

    /**
     * 往流里写一次。失败只标记断开，<b>不往外抛</b>。
     *
     * <p>这一点比看起来重要：客户端关掉页面时 send 会抛 IO 异常，而这一刻模型正在跑。
     * 让这个异常冒出去，这次生成会连着失败 —— 钱花了、结论没了、下次还得再花一次。
     * 断线是传输层的事，业务该照常跑完并落库。
     */
    private static void push(SseEmitter emitter, AtomicBoolean closed, SseEmitter.SseEventBuilder ev) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(ev);
        } catch (Exception e) {
            closed.set(true);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 同上
            }
        }
    }

    /**
     * 结果快照。给"重连、换设备、小程序轮询"用：只要 runId 在手，结论就能重新拿回来。
     *
     * <p>不区分角色：能读到 runId 的人本来就看过那份结论，再挡一次没有意义。
     */
    @GetMapping("/runs/{id}")
    public ResponseEntity<?> run(@PathVariable String id) {
        return agent.snapshot(id)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(view(r)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "没有这次生成记录：" + id, "code", "NO_RUN")));
    }

    /**
     * 主线体检：能不能干活、不能的话卡在哪一步、今天花了多少。
     *
     * <p>只读、0 token，监控可以按秒打。和 {@code /healthz} 的分工写在
     * {@link AgentService#health()} 里，一句话：那个是"进程活着吗"，这个是"功能能用吗"。
     *
     * <p><b>状态码只有两档</b>：库读得出来就 200（哪怕 DEGRADED），读不出来才 503。
     * 不给 DEGRADED 配 503 是刻意的 —— 它多半是 key 过期或模板文件写错，
     * 接上探针会让容器反复重启，而重启治不好这两件事。
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        try {
            return ResponseEntity.ok(agent.health());
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "DOWN");
            body.put("error", "读不了 agent_run 表：" + e.getMessage());
            return ResponseEntity.status(503).body(body);
        }
    }

    /** 请求体。字段名与文档 §9 一致；多出来的字段（window/threadId）当前忽略。 */
    public record AskBody(String templateId, String keywords, String window, String question,
                          String threadId, Boolean force) {
    }

    /**
     * 记账用的 actor。游客记自己的名字，其余记账号名；没开鉴权时记 {@code anonymous}。
     *
     * <p>必须和 {@link Roles#quotaActor} 的取法能对上：那边返回 null 表示"看全站"，
     * 这边如果也返回 null，{@code agent_run.actor} 就会一片空白，事后查"这 1 万 token 是谁花的"
     * 会完全没有线索。
     */
    private static String actorOf(HttpServletRequest req, String role) {
        Object u = req.getAttribute(BasicAuthFilter.USER_ATTR);
        if (u != null && !String.valueOf(u).isBlank()) {
            return String.valueOf(u);
        }
        return BasicAuthFilter.ROLE_RW.equals(role) ? "anonymous" : role;
    }

    /** 统一响应体。字段名是前端契约，改之前先看 web/src/api/agent.ts。 */
    private static Map<String, Object> view(AgentService.Result r) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("strategy", r.strategy());
        meta.put("candidates", r.candidates());
        meta.put("tokensIn", r.tokensIn());
        meta.put("tokensOut", r.tokensOut());
        meta.put("watermark", r.watermark());
        meta.put("cached", r.cached());
        meta.put("generatedAt", r.generatedAt());
        meta.put("model", r.model());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", r.runId());
        body.put("templateId", r.templateId());
        body.put("status", r.status());
        body.put("title", r.title());
        body.put("bullets", r.answer() == null ? List.of() : r.answer().bullets());
        body.put("reason", r.reason());
        body.put("detail", r.detail());
        body.put("note", r.note());
        // 语气交给服务端判：note 的文案会改，前端按字匹配的话改一次就静默失效
        body.put("noteKind", AgentService.noteKind(r.note()));
        // 过程是解释性数据：老数据为空数组，前端据此不渲染那一块
        body.put("steps", r.steps() == null ? List.of() : r.steps());
        body.put("meta", meta);
        return body;
    }

    private static ResponseEntity<?> error(AgentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        // 前端据此区分"等几秒再来"（BUSY）、"去改配置"（NOT_READY）、"今天别点了"（CAPPED）
        body.put("code", e.code());
        return ResponseEntity.status(e.status()).body(body);
    }
}
