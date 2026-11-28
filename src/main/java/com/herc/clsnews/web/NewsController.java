package com.herc.clsnews.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.ai.AnalysisException;
import com.herc.clsnews.ai.AnalysisService;
import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.job.IngestJob;
import com.herc.clsnews.model.News;
import com.herc.clsnews.model.NewsView;
import com.herc.clsnews.source.NewsSource;
import com.herc.clsnews.store.AccessLogStore;
import com.herc.clsnews.store.NewsStore;
import com.herc.clsnews.store.VisitStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class NewsController {

    /** 明细接口的时间格式。cn 使用者看「2026-11-13 11:02:33」比看 epoch 直观。 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NewsStore store;
    private final VisitStore visits;
    private final AccessLogStore access;
    private final IngestJob ingest;
    private final NewsSource source;
    private final AnalysisService analysis;
    private final AppProps props;
    private final ObjectMapper mapper;

    public NewsController(NewsStore store, VisitStore visits, AccessLogStore access, IngestJob ingest,
                          NewsSource source, AnalysisService analysis, AppProps props,
                          ObjectMapper mapper) {
        this.store = store;
        this.visits = visits;
        this.access = access;
        this.ingest = ingest;
        this.source = source;
        this.analysis = analysis;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * 列表接口。三种用法：
     *   GET /api/news                      首屏，按发布时间倒序，默认 10 条
     *   GET /api/news?since=12345          增量，返回 id 更大的条目（升序），一次最多 limit 条
     *   GET /api/news?before=12345         向下翻历史，返回 id 更小的条目（倒序）
     *
     * <p>since 与 before 互斥，同时给时 since 优先 —— 增量永远比翻历史更紧急。
     *
     * <p><b>游标契约</b>：响应里的 {@code nextSince} 是「这次真的交付出去的最后一条 id」，
     * 前端只能拿它推进游标；{@code hasMore} 表示游标之后还有积压，需要继续追。
     * 只在 {@code nextSince} 上推进是这套契约的全部要害 —— 换成「全局最大 id」就会
     * 在积压超过一页时把没交付的条目直接跳过（见 {@link com.herc.clsnews.store.NewsStore#since}）。
     * {@code maxId} 仍然返回，但只是为了兼容还没升级的老客户端，前端不再用它推进游标。
     */
    @GetMapping("/news")
    public Map<String, Object> news(@RequestParam(defaultValue = "10") int limit,
                                    @RequestParam(defaultValue = "0") long since,
                                    @RequestParam(defaultValue = "0") long before,
                                    HttpServletRequest req) {
        int n = Math.max(1, Math.min(limit, 200));
        List<News> rows;
        boolean hasMore;
        long nextSince;
        if (since > 0) {
            rows = store.since(since, n);
            // 空页时游标原地不动：退回 since 而不是 0，否则「没有新条目」会被当成「从头开始」
            nextSince = rows.isEmpty() ? since : rows.get(rows.size() - 1).id();
            // 按 hasMore 继续追是前端的责任，这里只如实回答「后面还有没有」
            hasMore = store.hasAfter(nextSince);
        } else if (before > 0) {
            rows = store.before(before, n);
            // 只有取满一页才可能还有；不满一页说明到底了
            hasMore = rows.size() >= n && store.countBefore(before) > rows.size();
            // 翻历史不推进增量游标：这一页是更旧的内容，拿它当游标会把中间一大段判成「已看过」
            nextSince = 0;
        } else {
            rows = store.latest(n);
            hasMore = store.count() > rows.size();
            // 首屏：本页最大 id 就是「已经给用户看过的最新位置」。
            // 不是全局最大 id —— 那等于替用户承认了没交付的那部分。
            nextSince = rows.stream().mapToLong(News::id).max().orElse(0);
        }
        List<NewsView> items = rows.stream().map(this::toView).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextSince", nextSince);
        body.put("hasMore", hasMore);
        // 保留字段：老前端拿它当游标，去掉会让还没刷新的页面直接不增量
        body.put("maxId", store.maxId());
        body.put("serverTime", Instant.now().getEpochSecond());
        body.put("stats", stats(roleOf(req)));
        return body;
    }

    /**
     * 关键词搜索。参数与返回结构刻意和 {@code /api/news} 对齐（items / hasMore / maxId），
     * 前端复用同一套渲染和翻页代码。
     *
     * <p>{@code /news/search} 不会被 {@code /news/{id}} 抢走：Spring 路径匹配里字面量
     * 优先级高于模板变量。
     */
    @GetMapping("/news/search")
    public Map<String, Object> search(@RequestParam(name = "q", required = false) String q,
                                      @RequestParam(defaultValue = "10") int limit,
                                      @RequestParam(defaultValue = "0") long before) {
        int n = Math.max(1, Math.min(limit, 200));
        String kw = q == null ? "" : q.trim();
        if (kw.length() > 50) {
            kw = kw.substring(0, 50);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // 空关键词不查库：LIKE '%%' 会命中全表，「搜索」就退化成了「列表」，
        // 而且前端会显示「找到 441 条」这种毫无意义的提示。
        if (kw.isEmpty()) {
            body.put("items", List.of());
            body.put("total", 0);
            body.put("hasMore", false);
            body.put("maxId", store.maxId());
            body.put("serverTime", Instant.now().getEpochSecond());
            return body;
        }

        String pattern = likePattern(kw);
        long cursor = before > 0 ? before : Long.MAX_VALUE;
        List<News> rows = store.search(pattern, cursor, n);

        body.put("items", rows.stream().map(this::toView).toList());
        body.put("total", store.countSearch(pattern));
        // 取满一页就认为可能还有：最后一页刚好取满时会多发一次请求拿到空数组，
        // 用一次多余的往返换「不必预先 count 每页剩余」，在 400 条量级上划算。
        body.put("hasMore", rows.size() >= n);
        body.put("maxId", store.maxId());
        body.put("serverTime", Instant.now().getEpochSecond());
        return body;
    }

    /**
     * 把用户输入编成 LIKE 模式。实现与转义的坑见 {@link LikePattern} ——
     * 抽出去只是为了让测试能直接断言「搜 100% 不会命中 100 元」。
     */
    private static String likePattern(String raw) {
        return LikePattern.of(raw);
    }

    @GetMapping("/news/{id}")
    public ResponseEntity<?> one(@PathVariable long id) {
        return store.findById(id)
                .<ResponseEntity<?>>map(n -> ResponseEntity.ok(toView(n)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "id 不存在")));
    }

    /**
     * 手动触发单条分析 —— 这是现在唯一会真的调用大模型的入口。
     *
     * <p>返回完整 NewsView 而不只是分析结果：前端拿到直接整块替换那一行的分析区，
     * 不用再发一次请求、也不用在前端拼一份平行的数据结构。
     *
     * @param force true = 无视「结果还新鲜」的保护强制重算。界面上没有这个入口，
     *              只留给运维手工调（改完 prompt 或换模型后重算某一条）
     */
    @PostMapping("/news/{id}/analyze")
    public ResponseEntity<?> analyze(@PathVariable long id,
                                     @RequestParam(defaultValue = "false") boolean force,
                                     HttpServletRequest req) {
        String role = roleOf(req);
        try {
            AnalysisService.Outcome o = analysis.analyzeNews(id, force, role, capOf(role));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("item", toView(o.item()));
            body.put("cached", o.cached());
            return ResponseEntity.ok(body);
        } catch (AnalysisException e) {
            // 错误码要单独给：前端据此决定是「等几秒自动重试」（BUSY）
            // 还是「直接显示红字让用户去改配置」（NOT_READY / CAPPED）
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            body.put("code", e.code());
            return ResponseEntity.status(e.status()).body(body);
        }
    }

    /**
     * 批量取一组 id 的分析状态，给页面「同步已展示条目」用。
     *
     * <p>为什么需要它：列表轮询只拉 since=最大 id 的**新**条目，永远不会重看已经渲染出来的行。
     * 10 个人共用一个账号时，同事分析了第 120 条，你屏幕上的第 120 条会一直空着 ——
     * 看起来像「他那边能分析，我这边不行」。
     */
    @GetMapping("/analysis/state")
    public Map<String, Object> analysisState(@RequestParam String ids, HttpServletRequest req) {
        List<Long> parsed = new ArrayList<>();
        for (String s : ids.split(",")) {
            if (parsed.size() >= 200) {
                break;
            }
            try {
                parsed.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                // 脏参数直接跳过，不为一个错误的 id 让整个同步请求失败
            }
        }
        Map<String, Object> items = new LinkedHashMap<>();
        for (News n : store.findByIds(parsed)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", n.analysisStatus());
            JsonNode a = null;
            if (n.analysisJson() != null && !n.analysisJson().isBlank()) {
                try {
                    a = mapper.readTree(n.analysisJson());
                } catch (Exception ignored) {
                    // 存进去的 JSON 坏了就当作没分析，别让同步接口整个 500
                }
            }
            m.put("analysis", a);
            items.put(String.valueOf(n.id()), m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        String role = roleOf(req);
        body.put("analyzedToday", analysis.usedToday(quotaActor(role)));
        body.put("capped", analysis.capped(quotaActor(role), capOf(role)));
        return body;
    }

    /** 把所有 failed 打回 pending 重跑。改完 max-tokens 或换 key 之后调用一次即可。 */
    @PostMapping("/analysis/retry-failed")
    public Map<String, Object> retryFailed() {
        int n = store.requeueFailed();
        return Map.of("requeued", n, "auto", props.getAnalysis().isAuto(),
                "hint", props.getAnalysis().isAuto()
                        ? "后台每 10 秒会取 3 条继续跑"
                        : "当前是手动模式，这些条目已重新变为可点击「分析」的状态");
    }

    /**
     * 运行状态。额外带一个 {@code role} 字段：前端靠它决定要不要显示「分析」这类写操作。
     *
     * <p>为什么不单独开 /api/whoami：页面启动本来就要打一次这个接口（判断有没有开鉴权），
     * 多一个字段比多一次往返划算。没开鉴权时没有角色属性，按 rw 处理 —— 那种状态下
     * 本来就没有保护，报 ro 只会让页面莫名其妙藏掉按钮。
     */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        String role = roleOf(req);
        Map<String, Object> body = new LinkedHashMap<>(stats(role));
        body.put("role", role);
        return body;
    }

    /**
     * 访客统计（聚合）。页面顶栏只用 today/total 两个数，其余给运维排查用。
     *
     * <p>这里只出聚合结果，<b>不含 IP、不含 UA</b> —— 明细在 {@link #visitLogs}，
     * 单独一个路径，因为它的受众和用途完全不同：这个接口是页面首屏顺带取的，
     * 那个是人为发起的排查动作。
     */
    @GetMapping("/visits")
    public Map<String, Object> visitStats(@RequestParam(defaultValue = "7") int days) {
        String today = LocalDate.now(ZoneId.systemDefault()).toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("today", visits.countToday(today));
        body.put("total", visits.countTotal());
        body.put("hitsToday", visits.hitsToday(today));
        // 明细口径的独立算法：拿 access_log 再算一遍今日人数，用来交叉验证聚合表。
        // 两个数字正常情况下相等或差一两个（明细有 60 秒限流），差很多就是写入出问题了。
        body.put("todayByVid", access.uvOnDay(today));
        body.put("devices", access.deviceStats(today));
        body.put("hours", access.hourHistogram(today));
        body.put("days", visits.recentDays(days));
        return body;
    }

    /**
     * 访客明细，给 curl 排查用。
     *
     * <pre>
     * 今天最新 50 条：  curl -u admin:xxx 'http://IP:8080/api/visits/logs'
     * 指定日期：        curl -u ... 'http://IP:8080/api/visits/logs?day=2026-11-12'
     * 查某个人：        curl -u ... 'http://IP:8080/api/visits/logs?ip=114.247.'
     * 只看微信进来的：  curl -u ... 'http://IP:8080/api/visits/logs?device=wechat'
     * 导出 CSV：        curl -u ... 'http://IP:8080/api/visits/logs?format=csv' -o visits.csv
     * </pre>
     *
     * <p><b>为什么可以在这里返回 IP</b>：这个路径在 {@code /api/**} 后面，需要 Basic 凭证，
     * 和 {@code /api/visits} 一样。区别是它把「谁能看到同事的 IP」这件事摆到了明面上 ——
     * 当前密码是十几个人共用的，任何拿到密码的人都能看到全部明细。
     * 对自用小工具来说这是可接受的取舍（换成每人一个账号成本远大于收益），
     * 但要清楚：<b>这个接口不能开给外部，也不能在没有 HTTPS 的情况下用</b>。
     *
     * <p><b>为什么默认只查一天</b>：没有 {@code day} 的查询要走全表扫。明细表 90 天清理，
     * 日增几百行，现在扫全表也就几万行不出事；但接口的形状决定了以后能怎么用，
     * 默认值给成「今天」比「全部」更难变成慢查询。
     */
    @GetMapping("/visits/logs")
    public ResponseEntity<?> visitLogs(@RequestParam(required = false) String day,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(defaultValue = "0") long before,
                                       @RequestParam(required = false) String ip,
                                       @RequestParam(required = false) String device,
                                       @RequestParam(defaultValue = "json") String format,
                                       HttpServletRequest req) {
        String target = day == null || day.isBlank()
                ? LocalDate.now(ZoneId.systemDefault()).toString()
                : day.trim();
        try {
            // 严格解析而不是「错了就当今天」：日期打错时静默返回今天的记录最误导人，
            // 你会以为昨天的数据没了
            LocalDate.parse(target);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "day 必须是 yyyy-MM-dd，例如 2026-11-13",
                    "got", target));
        }

        int n = Math.max(1, Math.min(limit, 500));
        List<AccessLogStore.LogRow> rows = access.list(target, ip, device, before, n);
        long total = access.countBy(target, ip, device);
        // 取满一页就认为可能还有：多一次往返换「不必精确算剩余」，和 /api/news 同一策略
        long nextBefore = rows.size() >= n ? rows.get(rows.size() - 1).id() : 0;

        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    // 不带 BOM：curl 管道给 awk/grep 时 BOM 会粘在第一列表头上
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .header("Content-Disposition", "attachment; filename=\"visits-" + target + ".csv\"")
                    .body(toCsv(rows));
        }

        boolean trustForwarded = props.getSecurity().isTrustForwardedHeaders();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("day", target);
        // 方便自查：这条 curl 自己是从哪个 IP 进来的，和你刚提交的那条记录对得上就说明按 IP 生效了
        body.put("requesterIp", ClientIp.of(req, trustForwarded));
        body.put("total", total);
        body.put("count", rows.size());
        body.put("hasMore", nextBefore > 0);
        body.put("nextBefore", nextBefore);
        body.put("items", rows.stream().map(NewsController::logView).toList());
        return ResponseEntity.ok(body);
    }

    /** 明细行转成对外结构。time 是给人看的，ts 是给脚本算的，两个都给。 */
    private static Map<String, Object> logView(AccessLogStore.LogRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("time", LocalDateTime.ofInstant(Instant.ofEpochSecond(r.ts()), ZoneId.systemDefault())
                .format(TIME_FMT));
        m.put("ts", r.ts());
        m.put("ip", r.ip());
        m.put("vid", r.vid());
        m.put("device", r.device());
        m.put("os", r.os());
        m.put("browser", r.browser());
        m.put("path", r.path());
        m.put("referer", r.referer());
        m.put("ua", r.ua());
        return m;
    }

    /**
     * 拼 CSV。字段一律加引号并转义内部引号 —— UA 里带逗号和双引号是常态
     * （{@code Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) ...}），
     * 不转义的话列会整体错位，比没有 CSV 更糟。
     */
    private static String toCsv(List<AccessLogStore.LogRow> rows) {
        StringBuilder sb = new StringBuilder("id,time,ip,vid,device,os,browser,path,referer,ua\n");
        for (AccessLogStore.LogRow r : rows) {
            String time = LocalDateTime.ofInstant(Instant.ofEpochSecond(r.ts()), ZoneId.systemDefault())
                    .format(TIME_FMT);
            csvField(sb, String.valueOf(r.id())).append(',');
            csvField(sb, time).append(',');
            csvField(sb, r.ip()).append(',');
            csvField(sb, r.vid()).append(',');
            csvField(sb, r.device()).append(',');
            csvField(sb, r.os()).append(',');
            csvField(sb, r.browser()).append(',');
            csvField(sb, r.path()).append(',');
            csvField(sb, r.referer()).append(',');
            csvField(sb, r.ua()).append('\n');
        }
        return sb.toString();
    }

    private static StringBuilder csvField(StringBuilder sb, String v) {
        sb.append('"');
        if (v != null) {
            sb.append(v.replace("\"", "\"\""));
        }
        return sb.append('"');
    }


    /** 角色与额度口径收在 {@link Roles}，和主线接口共用同一份实现。 */
    private String roleOf(HttpServletRequest req) {
        return Roles.of(req);
    }

    private Integer capOf(String role) {
        return Roles.capOf(role, props);
    }

    /** 展示用的上限数字。无独立上限时要落到全局值，不能把 null 丢给前端。 */
    private int capFor(String role) {
        Integer c = capOf(role);
        return c == null ? analysis.dailyCap() : c;
    }

    private String quotaActor(String role) {
        return Roles.quotaActor(role);
    }

    private Map<String, Object> stats(String role) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", store.count());
        s.put("pending", store.countByStatus("pending"));
        // 访客数字只给 rw / ro；游客连聚合数都不给。
        if (!BasicAuthFilter.ROLE_GUEST.equals(role)) {
            // 访客按「设备标识 + 自然日」去重：今日 = 今天来过的不同设备数，
            // 总 = 历史累计的不同设备数。拿不到 vid 的访问退回按 IP 算。
            String today = LocalDate.now(ZoneId.systemDefault()).toString();
            s.put("visitToday", visits.countToday(today));
            s.put("visitTotal", visits.countTotal());
        }
        s.put("source", source.name());
        s.put("intervalMs", props.getIntervalMs());
        s.put("lastRunAt", ingest.getLastRunAt());
        s.put("lastFetched", ingest.getLastFetched());
        s.put("lastInserted", ingest.getLastInserted());
        s.put("lastError", ingest.getLastError());
        s.put("runs", ingest.getTotalRuns());
        s.put("fulltext", props.getDisplay().isFulltext());
        s.put("analysisEnabled", analysis.enabled());
        // auto=false 是现在的默认：分析由用户点击触发，pending 表示「等你点」，不是「在排队」
        s.put("analysisAuto", props.getAnalysis().isAuto());
        s.put("analysisProvider", analysis.modelName());
        // 部署后第一眼看这个：false 说明 key 没配好，点击分析会全失败，但采集是正常的
        s.put("analysisReady", analysis.ready());
        // 配额按角色给：游客只看自己那 10 次，看到全站的 300 会以为额度很宽
        s.put("analyzedToday", analysis.usedToday(quotaActor(role)));
        s.put("analysisCap", capFor(role));
        s.put("analysisCapped", analysis.capped(quotaActor(role), capOf(role)));
        if (!analysis.usage().isBlank()) {
            s.put("analysisUsage", analysis.usage());
        }
        s.put("analyzed", store.countByStatus("done"));
        s.put("analysisFailed", store.countByStatus("failed"));
        return s;
    }

    private NewsView toView(News n) {
        JsonNode analysis = null;
        if (n.analysisJson() != null && !n.analysisJson().isBlank()) {
            try {
                analysis = mapper.readTree(n.analysisJson());
            } catch (Exception ignored) {
                // 存进去的 JSON 坏了就当作没分析，别让列表整个 500
            }
        }

        String text = n.content();
        if (!props.getDisplay().isFulltext() && text.length() > 60) {
            text = text.substring(0, 60) + "…";
        }

        return new NewsView(n.id(), n.publishTime(), n.level(), n.title(), text,
                n.url(), stocks(n.stocksJson()), analysis, n.analysisStatus());
    }

    private List<Map<String, String>> stocks(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructCollectionType(List.class,
                            mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class)));
        } catch (Exception e) {
            return List.of();
        }
    }
}
