package com.herc.clsnews.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 给用户看的那句话（{@code Result.note}）的宽度契约。
 *
 * <p>为什么值得单独一个测试类：这句话会直接渲染进浮层标题区，宽度只有 290 / 264 / 224px
 * （390 / 360 / 320 三档，右边还要站一个「刷新」按钮）。超一个字的后果不是报错，是
 * <b>第二行只剩半句</b>—— 一眼看得出没打磨过，但没有任何测试会因此变红。
 *
 * <p>2026-11-28 做 S1.4 原型时才量出来：{@code DELTA_LIMIT} 原来那句「今日已增量 3 次（上限 3 次），
 * 这次回全量，防止结论逐代漂移」是 29 个字，在 390px 上也折两行。因为没人量过，
 * 它就那么在代码里躺了一版。所以把宽度写成可执行的断言，而不是写在注释里等人自觉。
 *
 * <p>{@link #width} 的算法是「全角算 1、半角算 0.5」的近似：真正的行高由字体决定，
 * 但这条规则和浏览器实测的折行位置只差一个字，够用来卡上限了。
 */
class NoteCopyTest {

    private static final String TODAY = "2026-11-27";

    /**
     * 视觉宽度，全角 1、半角 0.5。
     *
     * <p>不按 {@code String.length()} 算：那样「新增 78 条」的 8 个字符会被当成 8 个字，
     * 实际只占 4.5 个字宽，卡出来的上限会过严，逼着人把中文删掉去凑数。
     */
    private static double width(String s) {
        double w = 0;
        for (char c : s.toCharArray()) {
            w += c > 0xFF ? 1 : 0.5;
        }
        return w;
    }

    private static Strategy.Decision decide(Strategy.Base base, long watermark, int added, int deltaToday) {
        return Strategy.decide(Strategy.Input.of(TODAY, watermark, base, added, deltaToday));
    }

    /** 七个分支各取一组有代表性的输入，数字取极值（1 位 / 3 位）把最长的情况也覆盖到。 */
    private static List<Strategy.Decision> everyBranch() {
        List<Strategy.Decision> all = new ArrayList<>();
        all.add(decide(null, 1012, 0, 0));                                        // NO_BASE
        all.add(decide(new Strategy.Base("r", 1000, "2026-11-26"), 1012, 12, 0)); // CROSS_DAY
        all.add(decide(new Strategy.Base("r", 1000, TODAY), 1000, 0, 0));         // SAME_WATERMARK
        all.add(decide(new Strategy.Base("r", 1000, TODAY), 1002, 2, 0));         // TOO_FEW_ADDED
        all.add(decide(new Strategy.Base("r", 1000, TODAY), 9000, 999, 0));       // TOO_MANY_ADDED，三位数
        all.add(decide(new Strategy.Base("r", 1000, TODAY), 1012, 12, 3));        // DELTA_LIMIT
        all.add(decide(new Strategy.Base("r", 1000, TODAY), 1012, 12, 0));        // DELTA
        return all;
    }

    @Test
    @DisplayName("七种判定给用户的话都不超过宽度上限")
    void everyNoteFitsOnOneLine() {
        for (Strategy.Decision d : everyBranch()) {
            String note = d.note();
            assertFalse(note == null || note.isBlank(), d.reason() + " 没给用户任何解释");
            assertTrue(width(note) <= Strategy.MAX_NOTE_WIDTH,
                    d.reason() + " 的提示宽 " + width(note) + " 字，超出上限 "
                            + Strategy.MAX_NOTE_WIDTH + "：" + note);
        }
    }

    @Test
    @DisplayName("AgentService 里三句降级提示也在上限内")
    void agentServiceNotesFit() {
        List<String> notes = List.of(
                Strategy.NOTE_SAME,
                AgentService.NOTE_COST_GATE,
                AgentService.NOTE_NO_PREV,
                AgentService.NOTE_ROLLBACK,
                AgentService.NOTE_NOT_CACHEABLE);
        for (String note : notes) {
            assertTrue(width(note) <= Strategy.MAX_NOTE_WIDTH,
                    "宽 " + width(note) + " 字，超上限：" + note);
        }
    }

    @Test
    @DisplayName("数据没变时两条返回路径说的是同一句话")
    void sameWatermarkCopyIsShared() {
        Strategy.Decision d = decide(new Strategy.Base("r", 1000, TODAY), 1000, 0, 0);

        assertEquals(Strategy.NOTE_SAME, d.note(),
                "命中缓存有两条路径（跨策略比对直接命中 / 判定阶段判出来），"
                        + "用两句不同的话，用户同一次操作会看到两种说法");
    }

    @Test
    @DisplayName("note 里不该出现过程量：日期、字符数、阈值这些只进日志")
    void noteHasNoInternalNumbers() {
        // 这条是防回归：29 字那句的毛病就是"把阈值和日期都塞进给用户的话里"。
        // 日期一旦出现就必定超宽（yyyy-MM-dd 就占 5 个字），所以拿它当哨兵。
        for (Strategy.Decision d : everyBranch()) {
            assertFalse(d.note().matches(".*\\d{4}-\\d{2}-\\d{2}.*"),
                    d.reason() + " 的提示里带了日期，该留在日志：" + d.note());
        }
    }

    @Test
    @DisplayName("上限本身要留出 320px 的余量")
    void limitStaysBelowNarrowestScreen() {
        // 224px / 11.5px ≈ 19.5 个字。上限高于 19 就等于放弃小屏
        assertTrue(Strategy.MAX_NOTE_WIDTH <= 19,
                "320px 屏只有约 19.5 个字宽，上限 " + Strategy.MAX_NOTE_WIDTH + " 已经放不下了");
        assertTrue(Strategy.MAX_NOTE_WIDTH >= 14,
                "卡太紧会逼着文案删掉关键信息（比如「新增几条」），14 是底线");
    }
}
