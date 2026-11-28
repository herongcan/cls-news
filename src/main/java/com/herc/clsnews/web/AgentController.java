package com.herc.clsnews.web;

import com.herc.clsnews.agent.AgentCatalog;
import com.herc.clsnews.agent.AgentException;
import com.herc.clsnews.agent.AgentService;
import com.herc.clsnews.agent.AgentTemplate;
import com.herc.clsnews.agent.Answer;
import com.herc.clsnews.config.AppProps;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final AgentService agent;
    private final AgentCatalog catalog;
    private final AppProps props;

    public AgentController(AgentService agent, AgentCatalog catalog, AppProps props) {
        this.agent = agent;
        this.catalog = catalog;
        this.props = props;
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
     * @param body templateId 必填；keywords 可选
     */
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody(required = false) AskBody body, HttpServletRequest req) {
        String role = Roles.of(req);
        String templateId = body == null ? null : body.templateId();
        String keywords = body == null ? null : body.keywords();
        try {
            AgentService.Result r = agent.ask(templateId, keywords, actorOf(req, role),
                    Roles.capOf(role, props));
            return ResponseEntity.ok(view(r));
        } catch (AgentException e) {
            return error(e);
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

    /** 请求体。字段名与文档 §9 一致；多出来的字段（window/threadId）当前忽略。 */    public record AskBody(String templateId, String keywords, String window, String question,
                          String threadId) {
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
