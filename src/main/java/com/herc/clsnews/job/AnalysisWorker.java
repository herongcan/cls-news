package com.herc.clsnews.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.ai.AnalysisResult;
import com.herc.clsnews.ai.Analyzer;
import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.model.News;
import com.herc.clsnews.store.NewsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 分析调度。刻意和采集解耦：
 *  - 采集只管入库，永远不被模型拖慢；
 *  - 这里只扫 pending，模型挂了不影响新闻流；
 *  - 有每日调用上限，防止某次配置错误把额度烧光。
 *
 * app.analysis.enabled=false 时整个 Bean 不创建。
 */
@Component
@ConditionalOnProperty(prefix = "app.analysis", name = "enabled", havingValue = "true")
public class AnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private static final int BATCH = 3;

    private final NewsStore store;
    private final Analyzer analyzer;
    private final AppProps props;
    private final ObjectMapper mapper;

    private LocalDate counterDate = LocalDate.now();
    private int usedToday = 0;
    /** 让「未就绪」只报一次，不然每 10 秒刷一行，日志会被自己淹没 */
    private boolean warnedNotReady = false;

    public AnalysisWorker(NewsStore store, Analyzer analyzer, AppProps props, ObjectMapper mapper) {
        this.store = store;
        this.analyzer = analyzer;
        this.props = props;
        this.mapper = mapper;
        // 上次进程被杀时可能留下 analyzing 状态的行，启动先打回 pending，不然永远无人处理
        int stuck = store.resetStuck();
        if (stuck > 0) {
            log.warn("发现 {} 条上次未完成的分析，已重新排队", stuck);
        }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void tick() {
        // 没配好 key 就直接不干。不能让它一条条去撞墙：那会把所有 pending 都刷成 failed，
        // 补好 key 之后还得手动 retry-failed 才能救回来。留在 pending 里等着是更对的状态。
        if (!analyzer.ready()) {
            if (!warnedNotReady) {
                log.error("分析器未就绪（多为 DEEPSEEK_API_KEY 未配置），已暂停分析；" +
                        "新闻采集不受影响。配好 key 并重启后会自动继续。");
                warnedNotReady = true;
            }
            return;
        }
        if (warnedNotReady) {
            log.info("分析器已就绪，恢复分析");
            warnedNotReady = false;
        }

        LocalDate today = LocalDate.now();
        if (!today.equals(counterDate)) {
            counterDate = today;
            usedToday = 0;
        }
        if (usedToday >= props.getAnalysis().getDailyCap()) {
            return;
        }

        List<News> batch = store.claimPending(BATCH);
        int failedInRow = 0;
        for (int i = 0; i < batch.size(); i++) {
            News n = batch.get(i);
            try {
                AnalysisResult r = analyzer.analyze(n);
                store.saveAnalysis(n.id(), mapper.writeValueAsString(r), r.model());
                usedToday++;
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

    public int getUsedToday() {
        return usedToday;
    }
}
