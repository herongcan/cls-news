package com.herc.clsnews.job;

import com.herc.clsnews.store.AccessLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 访问明细的保留期清理。
 *
 * <p>不清理的话这张表只增不减：按每天十几个访客算一年也有几千行，不算大，
 * 但"永久保留每个人的 IP 和浏览时间"本身是个需要理由的事 —— 没有理由，
 * 就设一个明确的期限。90 天足够覆盖"最近三个月谁在用"这类排查，再往前也没有意义。
 *
 * <p>04:40 是有意错开 03:30 的数据库备份：清理和备份同时跑会争同一个写锁，
 * 而备份失败比清理失败严重得多（Deleting 几行 vs 没有备份）。
 */
@Component
public class AccessLogCleaner {

    private static final Logger log = LoggerFactory.getLogger(AccessLogCleaner.class);

    private static final int KEEP_DAYS = 90;

    private final AccessLogStore access;

    public AccessLogCleaner(AccessLogStore access) {
        this.access = access;
    }

    @Scheduled(cron = "0 40 4 * * *")
    public void purge() {
        long cutoff = Instant.now().getEpochSecond() - (long) KEEP_DAYS * 86400;
        try {
            int n = access.purgeBefore(cutoff);
            if (n > 0) {
                log.info("access_log 清理 {} 行（保留 {} 天）", n, KEEP_DAYS);
            }
        } catch (Exception e) {
            // 清理失败不影响任何业务，下一轮再试即可
            log.warn("access_log 清理失败：{}", e.getMessage());
        }
    }
}
