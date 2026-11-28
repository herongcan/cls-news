package com.herc.clsnews.agent;

import com.herc.clsnews.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量的两条查询：base 从哪来、今天已经增量了几次。
 *
 * <p>这两条查询各有一个"看起来可以合并、实际不能"的点，所以单独测：
 * base 只能取 full（否则变成链式更新），计数只认 succeeded（否则会把 failed 也算成一次）。
 */
class BaseQueryTest {

    private static final String TODAY = "2026-11-27";
    private static final String YESTERDAY = "2026-11-26";
    private static final String P = "{\"templateId\":\"hot-today\",\"window\":\"1d\"}";

    @TempDir
    Path dir;

    private JdbcTemplate jdbc;
    private AgentRunStore store;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.create(dir);
        store = new AgentRunStore(jdbc);
    }

    private void succeed(String id, String windowKey, String params, String strategy,
                         String baseRunId, long watermark) {
        assertTrue(store.insertRunning(id, "hot-today", params, "admin", windowKey, strategy,
                baseRunId, watermark), id + " 应该抢得到");
        assertTrue(store.markSucceeded(id, "{\"title\":\"x\",\"bullets\":[]}", "[]", 10,
                100, 20, "m", 900));
    }

    /** created_at 是秒级的，同一秒插两行时排序不确定。手工拉开距离，模拟"先后生成"。 */
    private void age(String id, int seconds) {
        jdbc.update("UPDATE agent_run SET created_at = created_at - ? WHERE id = ?", seconds, id);
    }

    @Test
    @DisplayName("base 取当日最近一次 full")
    void baseIsLatestFull() {
        succeed("r_f1", TODAY, P, "full", null, 1000);
        succeed("r_f2", TODAY, P, "full", null, 1010);
        age("r_f2", -20);   // r_f2 更晚

        AgentRunStore.RunRow base = store.findBaseFull("hot-today", TODAY, P).orElseThrow();

        assertEquals("r_f2", base.id());
        assertEquals(1010L, base.watermark());
    }

    @Test
    @DisplayName("delta 不能当 base —— 否则变成链式更新")
    void deltaIsNeverBase() {
        succeed("r_f1", TODAY, P, "full", null, 1000);
        succeed("r_d1", TODAY, P, "delta", "r_f1", 1012);
        age("r_d1", -20);   // delta 比 full 新，若不过滤 strategy 会选中它

        AgentRunStore.RunRow base = store.findBaseFull("hot-today", TODAY, P).orElseThrow();

        assertEquals("r_f1", base.id(),
                "即使 delta 更新，base 也必须是 full：链式压缩三代之后主线会变成正确的废话");
    }

    @Test
    @DisplayName("昨天的 full 不能当今天的 base")
    void yesterdayFullIsNotTodayBase() {
        succeed("r_y", YESTERDAY, P, "full", null, 900);

        assertTrue(store.findBaseFull("hot-today", TODAY, P).isEmpty());
    }

    @Test
    @DisplayName("换了关键词就换了一套检索，不能共用 base")
    void differentParamsHasNoBase() {
        succeed("r_f1", TODAY, P, "full", null, 1000);

        assertTrue(store.findBaseFull("hot-today", TODAY, P.replace("1d", "1d\",\"keywords\":\"白银"))
                .isEmpty(), "params 不同意味着候选集不同，硬套 base 会让增量对不上");
    }

    @Test
    @DisplayName("只数成功的 delta：failed 不算一次")
    void countsOnlySucceededDelta() {
        succeed("r_f1", TODAY, P, "full", null, 1000);
        succeed("r_d1", TODAY, P, "delta", "r_f1", 1012);
        succeed("r_d2", TODAY, P, "delta", "r_f1", 1020);
        // 一次失败的 delta：它没产出结论，不该占用"今天还能增量几次"
        assertTrue(store.insertRunning("r_d3", "hot-today", P, "admin", TODAY, "delta", "r_f1", 1030));
        store.markFailed("r_d3", "模型挂了", 8000, 0, 5000);

        assertEquals(2, store.countDeltaToday("hot-today", TODAY, P));
    }

    @Test
    @DisplayName("跨天的 delta 计数互不影响")
    void deltaCountIsPerDay() {
        succeed("r_y", YESTERDAY, P, "full", null, 900);
        succeed("r_yd", YESTERDAY, P, "delta", "r_y", 950);
        succeed("r_t", TODAY, P, "full", null, 1000);

        assertEquals(1, store.countDeltaToday("hot-today", YESTERDAY, P));
        assertEquals(0, store.countDeltaToday("hot-today", TODAY, P));
    }
}
