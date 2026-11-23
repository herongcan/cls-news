package com.herc.clsnews.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.herc.clsnews.support.ApiHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /api/news} 的游标契约。
 *
 * <p>线上真实踩过的缺陷：响应里 {@code maxId} 用的是**全局**最大 id，而一次最多只返回
 * {@code limit} 条。前端把它当游标之后，停机/锁屏回来积压超过一页时，游标直接推到最末尾，
 * 没拿到的那一段永远不会再被拉 —— 症状是「少了几条，手动刷新才回来」。
 *
 * <p>现在的契约是：{@code nextSince} = 这次真的交付出去的最后一条，{@code hasMore} =
 * 后面还有没有积压。这里把「截断」「追平」「空页」三种情况都钉死。
 */
class NewsApiCursorTest {

    @TempDir
    Path dir;

    private ApiHarness api;

    @BeforeEach
    void setUp() throws Exception {
        api = ApiHarness.create(dir);
    }

    private List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    @Test
    @DisplayName("首屏 nextSince = 本页最大的 id（已交付的最新一条）")
    void firstPageNextSinceIsDeliveredMax() throws Exception {
        for (int i = 1; i <= 15; i++) {
            api.insert("n" + i, 1000 + i);
        }
        JsonNode page = api.getJson("/api/news", "limit", "10");

        assertThat(page.get("items")).hasSize(10);
        assertThat(page.get("nextSince").asLong())
                .as("首屏最后一页最大 id 就是用户的「已看到这里」")
                .isEqualTo(15);
        assertThat(page.get("hasMore").asBoolean()).as("下面还有 5 条历史").isTrue();
    }

    @Test
    @DisplayName("积压超过一页：nextSince 停在交付处，不能是全局 maxId")
    void truncatedSinceKeepsCursorAtDeliveredRow() throws Exception {
        // 先把游标推到 2，模拟「用户看到第 2 条就锁屏了」
        api.insert("a", 1000);
        api.insert("b", 1001);
        // 之后又进来 60 条（约等于停机 2 小时）
        for (int i = 3; i <= 62; i++) {
            api.insert("n" + i, 1000 + i);
        }

        JsonNode first = api.getJson("/api/news", "since", "2", "limit", "50");
        assertThat(first.get("items")).hasSize(50);
        assertThat(first.get("nextSince").asLong())
                .as("只交付了 50 条，游标必须停在交付处；旧实现这里会给 62，中间 10 条永久丢掉")
                .isEqualTo(52);
        assertThat(first.get("hasMore").asBoolean()).as("后面还有 10 条没交付").isTrue();
        assertThat(first.get("maxId").asLong())
                .as("maxId 仍然返回（兼容老客户端），但它不再是游标")
                .isEqualTo(62);

        JsonNode second = api.getJson("/api/news", "since", "52", "limit", "50");
        assertThat(ids(second)).isEqualTo(idsOf(53, 62));
        assertThat(second.get("nextSince").asLong()).isEqualTo(62);
        assertThat(second.get("hasMore").asBoolean()).as("追平了就不该让前端再发请求").isFalse();
    }

    @Test
    @DisplayName("增量的条目按 id 升序返回（前端反转后才是「新的在上」）")
    void sinceIsOrderedById() throws Exception {
        long a = api.insert("a", 100);
        api.insert("b", 101);
        // 回填：id 最大、发布时间最早
        api.insert("c", 50);

        JsonNode page = api.getJson("/api/news", "since", String.valueOf(a), "limit", "10");
        assertThat(ids(page)).isSorted();
        assertThat(ids(page)).hasSize(2);
    }

    @Test
    @DisplayName("没有新条目时游标原地不动，hasMore 为 false")
    void emptyPageKeepsCursor() throws Exception {
        for (int i = 1; i <= 3; i++) {
            api.insert("n" + i, 1000 + i);
        }
        JsonNode page = api.getJson("/api/news", "since", "3", "limit", "50");

        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("nextSince").asLong())
                .as("退回 0 会让前端以为「从头开始」，等于把整屏重发一遍")
                .isEqualTo(3);
        assertThat(page.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("翻历史（before）不推进增量游标")
    void beforeDoesNotTouchIncrementalCursor() throws Exception {
        for (int i = 1; i <= 10; i++) {
            api.insert("n" + i, 1000 + i);
        }
        JsonNode page = api.getJson("/api/news", "before", "5", "limit", "2");

        assertThat(page.get("nextSince").asLong())
                .as("拿更旧的一页当游标，中间那一段会被判成「已看过」")
                .isZero();
        assertThat(ids(page)).containsExactly(4L, 3L);
    }

    private static List<Long> idsOf(long from, long to) {
        List<Long> out = new ArrayList<>();
        for (long i = from; i <= to; i++) {
            out.add(i);
        }
        return out;
    }
}
