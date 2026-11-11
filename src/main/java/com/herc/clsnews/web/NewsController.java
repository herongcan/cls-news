package com.herc.clsnews.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.ai.AnalysisResult;
import com.herc.clsnews.ai.Analyzer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class NewsController {

    private final NewsStore store;
    private final IngestJob ingest;
    private final NewsSource source;
    private final Analyzer analyzer;
    private final AppProps props;
    private final ObjectMapper mapper;

    public NewsController(NewsStore store, IngestJob ingest, NewsSource source,
                          Analyzer analyzer, AppProps props, ObjectMapper mapper) {
        this.store = store;
        this.ingest = ingest;
        this.source = source;
        this.analyzer = analyzer;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * 列表接口。两种用法：
     *   GET /api/news                 首屏，按发布时间倒序
     *   GET /api/news?since=12345     增量，返回 id 更新的条目（升序）
     */
    @GetMapping("/news")
    public Map<String, Object> news(@RequestParam(defaultValue = "100") int limit,
                                    @RequestParam(defaultValue = "0") long since) {
        int n = Math.max(1, Math.min(limit, 200));
        List<News> rows = since > 0 ? store.since(since, n) : store.latest(n);
        List<NewsView> items = rows.stream().map(this::toView).toList();

        // 游标用全局最大 id，而不是本页最大值：首屏只取 100 条时，
        // 本页最大值可能小于库里真实最大值，下一轮会把已展示的条目当新增再发一遍。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("maxId", store.maxId());
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

    /** 手动触发单条分析。空实现阶段会返回 501 + 缺什么，不假装成功。 */
    @PostMapping("/news/{id}/analyze")
    public ResponseEntity<?> analyze(@PathVariable long id) {
        News n = store.findById(id).orElse(null);
        if (n == null) {
            return ResponseEntity.status(404).body(Map.of("error", "id 不存在"));
        }
        try {
            AnalysisResult r = analyzer.analyze(n);
            store.saveAnalysis(id, mapper.writeValueAsString(r), r.model());
            return ResponseEntity.ok(r);
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(501).body(Map.of("error", "LLM 未接入", "hint", e.getMessage()));
        } catch (Exception e) {
            store.markFailed(id);
            return ResponseEntity.status(500).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** 把所有 failed 打回 pending 重跑。改完 max-tokens 或换 key 之后调用一次即可。 */
    @PostMapping("/analysis/retry-failed")
    public Map<String, Object> retryFailed() {
        int n = store.requeueFailed();
        return Map.of("requeued", n, "hint", "AnalysisWorker 每 10 秒会取 3 条继续跑");
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
        s.put("analysisEnabled", props.getAnalysis().isEnabled());
        s.put("analysisProvider", analyzer.modelName());
        // 部署后第一眼看这个：false 说明 key 没配好，分析会全失败，但采集是正常的
        s.put("analysisReady", analyzer.ready());
        if (!analyzer.usage().isBlank()) {
            s.put("analysisUsage", analyzer.usage());
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
