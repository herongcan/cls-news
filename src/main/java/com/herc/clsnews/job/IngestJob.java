package com.herc.clsnews.job;

import com.herc.clsnews.model.RawItem;
import com.herc.clsnews.source.NewsSource;
import com.herc.clsnews.store.NewsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 抓取调度。固定延迟（fixedDelay）而不是固定频率：上一轮没跑完不会叠加，
 * 慢的时候自动降频，这对被采集方更礼貌，也避免自己打自己。
 */
@Component
public class IngestJob {

    private static final Logger log = LoggerFactory.getLogger(IngestJob.class);

    private final NewsSource source;
    private final NewsStore store;

    private volatile long lastRunAt = 0;
    private volatile int lastFetched = 0;
    private volatile int lastInserted = 0;
    private volatile String lastError;
    private volatile long totalRuns = 0;

    public IngestJob(NewsSource source, NewsStore store) {
        this.source = source;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${app.interval-ms:90000}",
               initialDelayString = "${app.initial-delay-ms:1500}")
    public synchronized void ingest() {
        try {
            List<RawItem> items = source.fetch();
            // 关键：接口是按「最新优先」返回的，必须按发布时间升序入库，
            // 否则自增 id 的顺序和发布时间相反，增量游标（id > maxId）会漏或重。
            items.sort(Comparator.comparingLong(RawItem::publishTime)
                    .thenComparing(RawItem::itemId));

            int added = 0;
            for (RawItem it : items) {
                added += store.insertIfAbsent(it);
            }
            lastFetched = items.size();
            lastInserted = added;
            lastError = null;
            if (added > 0) {
                log.info("抓取完成：拉取 {} 条，新增 {} 条", items.size(), added);
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("抓取失败（下一轮继续）：{}", lastError);
        } finally {
            lastRunAt = Instant.now().getEpochSecond();
            totalRuns++;
        }
    }

    public long getLastRunAt() { return lastRunAt; }
    public int getLastFetched() { return lastFetched; }
    public int getLastInserted() { return lastInserted; }
    public String getLastError() { return lastError; }
    public long getTotalRuns() { return totalRuns; }
}
