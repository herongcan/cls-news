package com.herc.clsnews.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.ai.AnalysisResult;
import com.herc.clsnews.ai.AnalysisService;
import com.herc.clsnews.ai.Analyzer;
import com.herc.clsnews.model.News;
import com.herc.clsnews.store.NewsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 后台自动消化 pending 队列。**默认关闭**，分析改由用户在页面上逐条点击触发。
 *
 * <p>要恢复「抓进来就自动分析」的老行为，把 app.analysis.auto 设成 true。
 * 两条链路共用 {@link AnalysisService}，配额、就绪判定、落库只有一份实现，
 * 不会出现「手动能跑自动跑不了」这种两套逻辑各自腐化的情况。
 *
 * <p>同时要求 enabled=true 和 auto=true —— 前者是总闸，后者只决定要不要后台跑。
 */
@Component
@ConditionalOnProperty(prefix = "app.analysis", name = {"enabled", "auto"}, havingValue = "true")
public class AnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private static final int BATCH = 3;

    private final NewsStore store;
    private final Analyzer analyzer;
    private final AnalysisService service;
    private final ObjectMapper mapper;

    /** 让「未就绪」「配额用完」各只报一次，不然每 10 秒刷一行，日志会被自己淹没 */
    private boolean warnedNotReady = false;
    private boolean warnedCapped = false;

    public AnalysisWorker(NewsStore store, Analyzer analyzer, AnalysisService service, ObjectMapper mapper) {
        this.store = store;
        this.analyzer = analyzer;
        this.service = service;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void tick() {
        // 没配好 key 就直接不干。不能让它一条条去撞墙：那会把所有 pending 都刷成 failed，
        // 补好 key 之后还得手动 retry-failed 才能救回来。留在 pending 里等着是更对的状态。
        if (!service.ready()) {
            if (!warnedNotReady) {
                log.error("分析器未就绪（多为 DEEPSEEK_API_KEY 未配置），后台分析暂停；" +
                        "新闻采集不受影响。配好 key 并重启后会自动继续。");
                warnedNotReady = true;
            }
            return;
        }
        if (warnedNotReady) {
            log.info("分析器已就绪，恢复后台分析");
            warnedNotReady = false;
        }

        // 配额判断交给 Service：它查库算今日用量，重启不丢，和手动点击共用同一份口径。
        // 这里一定要打日志 —— 旧实现是静默 return，表现为「新消息永远停在待分析、日志一个字都没有」，
        // 花半天都找不到原因。
        if (service.capped()) {
            if (!warnedCapped) {
                log.warn("今日分析已达上限 {} 条，后台分析暂停到明天（app.analysis.daily-cap 可调）",
                        service.dailyCap());
                warnedCapped = true;
            }
            return;
        }
        warnedCapped = false;

        List<News> batch = store.claimPending(BATCH);
        int failedInRow = 0;
        for (int i = 0; i < batch.size(); i++) {
            News n = batch.get(i);
            try {
                // 和手动链路共用同一本账，否则 auto 模式跑的量在 /api/status 里看不见。
                // actor 记 auto：后台跑的不该算到演示账号那 10 次额度上。
                store.recordCall(n.id(), "auto");
                AnalysisResult r = analyzer.analyze(n);
                store.saveAnalysis(n.id(), mapper.writeValueAsString(r), r.model());
                failedInRow = 0;
                log.info("分析完成 id={} sentiment={}", n.id(), r.sentiment());
            } catch (Exception e) {
                store.markFailed(n.id());
                failedInRow++;
                log.warn("分析失败 id={}：{}", n.id(), e.getMessage());
                // 连续失败说明 key/余额/网络出了问题，继续打只会白烧时间和请求，这一轮先停
                if (failedInRow >= 2) {
                    // 本批里还没轮到的几条已经被 claim 成 analyzing 了，必须打回 pending，
                    // 否则它们会一直挂着，直到下次重启被 resetStuck 捡回来 —— 期间无声无息。
                    List<Long> rest = batch.subList(i + 1, batch.size()).stream().map(News::id).toList();
                    int back = store.releaseClaims(rest);
                    log.error("连续 {} 条分析失败，本轮中止（{} 条回退到 pending）。检查 key、余额或网络", failedInRow, back);
                    return;
                }
            }
        }
    }
}
