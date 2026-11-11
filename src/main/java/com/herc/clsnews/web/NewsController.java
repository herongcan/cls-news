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
import com.herc.clsnews.store.NewsStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class NewsController {

    private final NewsStore store;
    private final IngestJob ingest;
    private final NewsSource source;
    private final AnalysisService analysis;
    private final AppProps props;
    private final ObjectMapper mapper;

    public NewsController(NewsStore store, IngestJob ingest, NewsSource source,
                          AnalysisService analysis, AppProps props, ObjectMapper mapper) {
        this.store = store;
        this.ingest = ingest;
        this.source = source;
        this.analysis = analysis;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * 列表接口。三种用法：
     *   GET /api/news                      首屏，按发布时间倒序，默认 10 条
     *   GET /api/news?since=12345          增量，返回 id 更大的条目（升序）
     *   GET /api/news?before=12345         向下翻历史，返回 id 更小的条目（倒序）
     *
     * <p>since 与 before 互斥，同时给时 since 优先 —— 增量永远比翻历史更紧急。
     */
    @GetMapping("/news")
    public Map<String, Object> news(@RequestParam(defaultValue = "10") int limit,
                                    @RequestParam(defaultValue = "0") long since,
                                    @RequestParam(defaultValue = "0") long before) {
        int n = Math.max(1, Math.min(limit, 200));
        List<News> rows;
        boolean hasMore;
        if (since > 0) {
            // 增量不分页：积压多少一次取走，免得前端还要循环追
            rows = store.since(since, n);
            hasMore = false;
        } else if (before > 0) {
            rows = store.before(before, n);
            // 只有取满一页才可能还有；不满一页说明到底了
            hasMore = rows.size() >= n && store.countBefore(before) > rows.size();
        } else {
            rows = store.latest(n);
            hasMore = store.count() > rows.size();
        }
        List<NewsView> items = rows.stream().map(this::toView).toList();

        // 游标用全局最大 id，而不是本页最大值：首屏只取 10 条时，
        // 本页最大值可能小于库里真实最大值，下一轮会把已展示的条目当新增再发一遍。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("maxId", store.maxId());
        body.put("hasMore", hasMore);
        body.put("serverTime", Instant.now().getEpochSecond());
        body.put("stats", stats());
        return body;
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
     * @param force true = 无视「结果还新鲜」的保护强制重算，对应界面上的「重新分析」
     */
    @PostMapping("/news/{id}/analyze")
    public ResponseEntity<?> analyze(@PathVariable long id,
                                     @RequestParam(defaultValue = "false") boolean force) {
        try {
            AnalysisService.Outcome o = analysis.analyzeNews(id, force);
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
    public Map<String, Object> analysisState(@RequestParam String ids) {
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
        body.put("analyzedToday", analysis.usedToday());
        body.put("capped", analysis.capped());
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

    @GetMapping("/status")
    public Map<String, Object> status() {
        return stats();
    }

    private Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", store.count());
        s.put("pending", store.countByStatus("pending"));
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
        s.put("analyzedToday", analysis.usedToday());
        s.put("analysisCap", analysis.dailyCap());
        s.put("analysisCapped", analysis.capped());
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
