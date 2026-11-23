package com.herc.clsnews.store;

import com.herc.clsnews.model.News;
import com.herc.clsnews.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 游标查询的边界。这是线上出过事的地方，所以断言写得比较细。
 *
 * <p>背景：{@code since} 的过滤键是 id，排序键曾经是 {@code publish_time}。
 * 两者不一致时，一页装不下的那次（停机/离线/补数据后积压超过 limit）会让
 * 「本页最后一条」和「本页最大 id」不是同一行 —— 回填进来的老消息 id 大、
 * 发布时间小，排在最前面占满一页，游标落在它身上，中间那批就永久跳过。
 */
class NewsStoreCursorTest {

    @TempDir
    Path dir;

    private JdbcTemplate jdbc;
    private NewsStore store;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.create(dir);
        store = new NewsStore(jdbc);
    }

    private List<Long> ids(List<News> rows) {
        return rows.stream().map(News::id).toList();
    }

    @Test
    @DisplayName("since 按 id 升序，不按发布时间：回填的老条目不能插到前面占位")
    void sinceOrdersByIdNotPublishTime() {
        long a = TestDb.insert(jdbc, "a", 100);
        long b = TestDb.insert(jdbc, "b", 101);
        // 回填：id 最大，但发布时间最早
        long c = TestDb.insert(jdbc, "c", 50);

        assertThat(ids(store.since(a, 10)))
                .as("过滤键是 id，排序键也必须(id)，否则游标落在 c 上会把 b 跳过")
                .containsExactly(b, c);
    }

    @Test
    @DisplayName("since 被 limit 截断时，本页最后一条就是最大 id，不丢中间的条目")
    void sinceTruncatedKeepsCursorOnLastDeliveredRow() {
        long a = TestDb.insert(jdbc, "a", 100);
        long b = TestDb.insert(jdbc, "b", 101);
        long c = TestDb.insert(jdbc, "c", 50);   // 回填，发布最早

        List<News> page1 = store.since(a, 1);
        assertThat(ids(page1)).as("按发布时间排会先给 c（id 最大但最旧），b 就再也拿不到了")
                .containsExactly(b);
        assertThat(store.hasAfter(page1.get(0).id())).isTrue();

        // 按页面回来的游标继续追，能拿到剩下的那条
        assertThat(ids(store.since(page1.get(0).id(), 1))).containsExactly(c);
    }

    @Test
    @DisplayName("since 不含游标本身")
    void sinceExcludesCursor() {
        long a = TestDb.insert(jdbc, "a", 100);
        long b = TestDb.insert(jdbc, "b", 101);
        assertThat(ids(store.since(a, 10))).containsExactly(b);
        assertThat(ids(store.since(b, 10))).isEmpty();
    }

    @Test
    @DisplayName("hasAfter：没有更新的一条时返回 false，不能一直追")
    void hasAfterReportsTail() {
        long a = TestDb.insert(jdbc, "a", 100);
        long b = TestDb.insert(jdbc, "b", 101);
        assertThat(store.hasAfter(a)).isTrue();
        assertThat(store.hasAfter(b)).as("已经到最后一条，hasMore 必须是 false，否则前端空转")
                .isFalse();
        assertThat(store.hasAfter(0)).isTrue();
    }

    @Test
    @DisplayName("before 翻历史：不重不漏，翻到底返回空")
    void beforePagingIsStable() {
        for (int i = 1; i <= 10; i++) {
            TestDb.insert(jdbc, "n" + i, 1000 + i);
        }
        List<News> p1 = store.before(11, 4);
        List<News> p2 = store.before(p1.get(p1.size() - 1).id(), 4);
        List<News> p3 = store.before(p2.get(p2.size() - 1).id(), 4);

        assertThat(ids(p1)).hasSize(4).isSortedAccordingTo((x, y) -> Long.compare(y, x));
        // 三页拼起来是 10..1，正好覆盖全部且互不重叠
        List<Long> all = new java.util.ArrayList<>(ids(p1));
        all.addAll(ids(p2));
        all.addAll(ids(p3));
        assertThat(all).containsExactly(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
        assertThat(store.before(1, 4)).isEmpty();
    }

    @Test
    @DisplayName("countBefore 只数游标之前，用来判断还能不能翻")
    void countBeforeBoundary() {
        for (int i = 1; i <= 10; i++) {
            TestDb.insert(jdbc, "n" + i, 1000 + i);
        }
        assertThat(store.countBefore(1)).isZero();
        assertThat(store.countBefore(2)).isEqualTo(1);
        assertThat(store.countBefore(11)).isEqualTo(10);
    }

    @Test
    @DisplayName("首屏按发布时间倒序，与 id 顺序无关")
    void latestOrdersByPublishTimeDesc() {
        long old = TestDb.insert(jdbc, "old", 100);
        long newest = TestDb.insert(jdbc, "newest", 300);
        long mid = TestDb.insert(jdbc, "mid", 200);

        assertThat(ids(store.latest(3))).containsExactly(newest, mid, old);
    }

    @Test
    @DisplayName("空库时所有游标查询都安全，不抛异常")
    void emptyDatabaseIsSafe() {
        assertThat(store.latest(10)).isEmpty();
        assertThat(store.since(0, 10)).isEmpty();
        assertThat(store.before(10, 10)).isEmpty();
        assertThat(store.hasAfter(0)).isFalse();
        assertThat(store.maxId()).isZero();
    }
}
