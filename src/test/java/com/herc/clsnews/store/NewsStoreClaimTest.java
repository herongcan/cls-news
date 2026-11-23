package com.herc.clsnews.store;

import com.herc.clsnews.model.News;
import com.herc.clsnews.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分析任务的状态迁移。
 *
 * <p>这一组是「花钱」的那部分：认领错了就是同一条消息被调两次模型，
 * 释放漏了就是条目永远停在 analyzing，页面上看起来「点了没反应」。
 * 都是不会报错、只会静默出错的类型，最适合用测试固定住。
 */
class NewsStoreClaimTest {

    @TempDir
    Path dir;

    private JdbcTemplate jdbc;
    private NewsStore store;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.create(dir);
        store = new NewsStore(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("claimOne 成功把 pending 改成 analyzing，第二次抢同一条拿空")
    void claimOneIsExclusive() {
        long id = TestDb.insert(jdbc, "a", 100);

        Optional<News> first = store.claimOne(id);
        assertThat(first).isPresent();
        assertThat(first.get().analysisStatus()).isEqualTo("analyzing");
        assertThat(TestDb.status(jdbc, id)).isEqualTo("analyzing");

        assertThat(store.claimOne(id))
                .as("已经有人在算还放行，就是两次扣费")
                .isEmpty();
    }

    @Test
    @DisplayName("claimOne 对 done 的行放行（运维 force 重算走这条），对不存在返回空")
    void claimOneAllowsRedoButNotMissing() {
        long id = TestDb.insert(jdbc, "a", 100);
        TestDb.setStatus(jdbc, id, "done");
        assertThat(store.claimOne(id)).as("force 重算必须能认领 done 的行").isPresent();

        assertThat(store.claimOne(9999)).isEmpty();
    }

    @Test
    @DisplayName("claimPending 按 id 升序取 oldest first，且不碰非 pending")
    void claimPendingTakesOldestFirst() {
        long a = TestDb.insert(jdbc, "a", 300);
        long b = TestDb.insert(jdbc, "b", 200);
        long c = TestDb.insert(jdbc, "c", 100);
        TestDb.setStatus(jdbc, b, "done");

        List<News> claimed = store.claimPending(2);
        assertThat(claimed).extracting(News::id).containsExactly(a, c);
        assertThat(TestDb.status(jdbc, b)).as("done 的行不该被后台队列重新捡起来").isEqualTo("done");
        assertThat(store.claimPending(5)).as("已认领的不再重复返回").isEmpty();
    }

    @Test
    @DisplayName("releaseClaims 只释放指定的行，且只释放 analyzing 的")
    void releaseClaimsIsNarrow() {
        long a = TestDb.insert(jdbc, "a", 100);
        long b = TestDb.insert(jdbc, "b", 101);
        long c = TestDb.insert(jdbc, "c", 102);
        store.claimOne(a);
        store.claimOne(b);
        TestDb.setStatus(jdbc, c, "done");

        assertThat(store.releaseClaims(List.of(a, c))).isEqualTo(1);
        assertThat(TestDb.status(jdbc, a)).isEqualTo("pending");
        assertThat(TestDb.status(jdbc, b)).as("没点名的行不要动，别人可能正在算").isEqualTo("analyzing");
        assertThat(TestDb.status(jdbc, c)).as("done 不能被释放成 pending，否则白算一遍").isEqualTo("done");
    }

    @Test
    @DisplayName("releaseClaims 传空不炸")
    void releaseClaimsEmptyIsNoop() {
        assertThat(store.releaseClaims(List.of())).isZero();
        assertThat(store.releaseClaims(null)).isZero();
    }

    @Test
    @DisplayName("resetStuck 把所有 analyzing 打回 pending：进程被杀后的恢复")
    void resetStuckResetsAll() {
        long a = TestDb.insert(jdbc, "a", 100);
        long b = TestDb.insert(jdbc, "b", 101);
        TestDb.setStatus(jdbc, a, "analyzing");
        TestDb.setStatus(jdbc, b, "analyzing");

        assertThat(store.resetStuck()).isEqualTo(2);
        assertThat(TestDb.status(jdbc, a)).isEqualTo("pending");
        assertThat(TestDb.status(jdbc, b)).isEqualTo("pending");
        assertThat(store.resetStuck()).as("没有 stuck 时返回 0，启动日志才不会乱叫").isZero();
    }

    @Test
    @DisplayName("两个线程同时抢同一条，只有一个成功")
    void concurrentClaimOnlyOneWins() throws Exception {
        long id = TestDb.insert(jdbc, "a", 100);
        pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Boolean> f1 = pool.submit(() -> claimAfter(start, id));
        Future<Boolean> f2 = pool.submit(() -> claimAfter(start, id));
        start.countDown();

        int wins = (f1.get(10, TimeUnit.SECONDS) ? 1 : 0) + (f2.get(10, TimeUnit.SECONDS) ? 1 : 0);
        assertThat(wins).as("两个都成功 = 同一条消息调了两次模型").isEqualTo(1);
    }

    private boolean claimAfter(CountDownLatch start, long id) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return store.claimOne(id).isPresent();
    }
}
