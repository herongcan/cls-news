package com.herc.clsnews.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 三档判定的全部分支。
 *
 * <p>这个测试是"省钱"这件事唯一的自动化保证：{@link Strategy#decide} 一旦把某个分支判错，
 * 表现不是报错，而是账单变高。所以每个分支都必须有单独一条，
 * 且边界值（2/3、65/66、2/3 次）要两侧都测 —— 只测中间值的话，把 {@code <} 写成 {@code <=}
 * 根本发现不了。
 */
class DeltaStrategyTest {

    private static final String TODAY = "2026-11-27";
    private static final String YESTERDAY = "2026-11-26";

    /** 一个水位线 1000 的当日 full。 */
    private static Strategy.Base base() {
        return new Strategy.Base("r_full", 1000, TODAY);
    }

    private static Strategy.Decision decide(Long baseWatermark, String baseWindow, long watermark,
                                            int added, int deltaToday) {
        Strategy.Base b = baseWatermark == null ? null
                : new Strategy.Base("r_full", baseWatermark, baseWindow);
        return Strategy.decide(Strategy.Input.of(TODAY, watermark, b, added, deltaToday));
    }

    @Test
    @DisplayName("今天没有 full → 全量")
    void noBaseMeansFull() {
        Strategy.Decision d = decide(null, null, 1012, 0, 0);

        assertEquals(Strategy.Mode.FULL, d.mode());
        assertEquals(Strategy.Reason.NO_BASE, d.reason());
        assertNull(d.base(), "没有 base 就不该编一个出来");
    }

    @Test
    @DisplayName("base 是昨天的 → 全量，不拿旧结论当底")
    void crossDayMeansFull() {
        Strategy.Decision d = decide(1000L, YESTERDAY, 1012, 12, 0);

        assertEquals(Strategy.Mode.FULL, d.mode());
        assertEquals(Strategy.Reason.CROSS_DAY, d.reason());
        assertNull(d.base(), "跨天的 base 不能带下去，否则会把昨天的结论继承进今天");
    }

    @Test
    @DisplayName("水位线与 base 相同 → 缓存，0 成本")
    void sameWatermarkMeansCache() {
        Strategy.Decision d = decide(1000L, TODAY, 1000, 0, 0);

        assertEquals(Strategy.Mode.CACHE, d.mode());
        assertEquals(Strategy.Reason.SAME_WATERMARK, d.reason());
        assertNotNull(d.base(), "缓存要能取回那份结论，所以 base 必须带出来");
        assertEquals("r_full", d.base().runId());
    }

    @Test
    @DisplayName("新增 2 条 → 缓存；新增 3 条 → 增量（阈值两侧）")
    void addedThresholdIsThree() {
        Strategy.Decision two = decide(1000L, TODAY, 1002, 2, 0);
        assertEquals(Strategy.Mode.CACHE, two.mode(), "2 条不值得重排一次主线");
        assertEquals(Strategy.Reason.TOO_FEW_ADDED, two.reason());

        Strategy.Decision three = decide(1000L, TODAY, 1003, 3, 0);
        assertEquals(Strategy.Mode.DELTA, three.mode(), "3 条是下边界，必须走增量");
        assertEquals(Strategy.Reason.DELTA, three.reason());
    }

    @Test
    @DisplayName("新增 12 条 → 增量")
    void typicalIncreaseMeansDelta() {
        Strategy.Decision d = decide(1000L, TODAY, 1012, 12, 0);

        assertEquals(Strategy.Mode.DELTA, d.mode());
        assertEquals(Strategy.Reason.DELTA, d.reason());
        assertEquals(1000L, d.base().watermark(), "切分新旧条目靠这个水位线");
    }

    @Test
    @DisplayName("新增 65 条 → 增量；66 条 → 全量（交叉点两侧）")
    void addedCeilingIsSixtyFive() {
        Strategy.Decision atCap = decide(1000L, TODAY, 1065, 65, 0);
        assertEquals(Strategy.Mode.DELTA, atCap.mode(), "65 是上边界，仍走增量");

        Strategy.Decision over = decide(1000L, TODAY, 1066, 66, 0);
        assertEquals(Strategy.Mode.FULL, over.mode(), "66 条时增量的固定开销已经不划算");
        assertEquals(Strategy.Reason.TOO_MANY_ADDED, over.reason());
    }

    @Test
    @DisplayName("同日增量已满 3 次 → 全量（第三次仍可增量）")
    void deltaLimitForcesFull() {
        Strategy.Decision second = decide(1000L, TODAY, 1012, 12, 2);
        assertEquals(Strategy.Mode.DELTA, second.mode(), "第 2 次之后还剩一次");

        Strategy.Decision third = decide(1000L, TODAY, 1012, 12, 3);
        assertEquals(Strategy.Mode.FULL, third.mode(), "满 3 次回全量，把结论拉回客观基准");
        assertEquals(Strategy.Reason.DELTA_LIMIT, third.reason());
    }

    @Test
    @DisplayName("水位线推进但候选没变（补采了窗口外的新闻）→ 缓存，不是全量")
    void watermarkMovedButCandidatesSame() {
        // 采集补进来一条前天的新闻：max(id) 涨了，但今日窗口里条数没变。
        // 这时按"水位线不同"判增量，会花 6000 token 重排出一份和上一版一模一样的主线。
        Strategy.Decision d = decide(1000L, TODAY, 1001, 0, 0);

        assertEquals(Strategy.Mode.CACHE, d.mode());
        assertEquals(Strategy.Reason.TOO_FEW_ADDED, d.reason(),
                "不是 SAME_WATERMARK —— 水位线确实动了，只是候选集没动");
    }

    @Test
    @DisplayName("added 传负数时按 0 处理，不静默走缓存以外的分支")
    void negativeAddedIsClamped() {
        Strategy.Decision d = decide(1000L, TODAY, 1012, -5, 0);

        assertEquals(Strategy.Mode.CACHE, d.mode());
        assertEquals(Strategy.Reason.TOO_FEW_ADDED, d.reason());
    }

    @Test
    @DisplayName("阈值可注入：limit=1 时第二次就回全量")
    void limitIsConfigurable() {
        Strategy.Base b = base();
        Strategy.Decision first = Strategy.decide(
                new Strategy.Input(TODAY, 1012, b, 12, 0, 1));
        assertEquals(Strategy.Mode.DELTA, first.mode());

        Strategy.Decision second = Strategy.decide(
                new Strategy.Input(TODAY, 1012, b, 12, 1, 1));
        assertEquals(Strategy.Mode.FULL, second.mode());
    }

    @Test
    @DisplayName("判定顺序：水位线相同优先于新增不足")
    void orderMatters() {
        // 两个条件同时成立（watermark 相同意味着 added 必然 0），
        // 但给用户的说法必须是"数据没变"，而不是"新增 0 条不够 3 条"
        Strategy.Decision d = decide(1000L, TODAY, 1000, 0, 0);
        assertEquals(Strategy.Reason.SAME_WATERMARK, d.reason());
    }
}
