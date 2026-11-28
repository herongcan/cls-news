package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层打包的预算测试。
 *
 * <p>这些断言的共同目的：把"600 条会不会炸"从一次真调用变成一次 5 毫秒的单测。
 * 预算类问题最贵的不是修，是发现得晚 —— 上线后账单涨了 6 倍才知道，已经花出去了。
 *
 * <p>标题与正文长度按实测值造（标题 28 字、正文 170 字），不是随手写一句短标题：
 * token 预估直接是长度乘以条数，用短标题造数会把 600 条场景算成 1.6 万，
 * 掩盖掉真实的 2.5 万 —— 这种"测试造数比现实更乐观"的坑，比没有测试更危险。
 */
class PackingTest {

    /** 实测平均标题长度。 */
    private static final int TITLE_CHARS = 28;

    /** 实测平均正文长度。 */
    private static final int CONTENT_CHARS = 170;

    private static NewsRow row(long id, String title, String content, long t) {
        return new NewsRow(id, title, content, t, "C");
    }

    /** 高分条目：金额 + 公司行为 = 6 分，够格进 L1。 */
    private static NewsRow strong(long id, long t) {
        return row(id, fixed("某公司拟回购2亿元股份", TITLE_CHARS), body(CONTENT_CHARS), t);
    }

    /** 低分条目：纯评论无数字 = -1 分，只能进 L2。 */
    private static NewsRow weak(long id, long t) {
        return row(id, fixed("分析师认为市场情绪偏暖", TITLE_CHARS), body(CONTENT_CHARS), t);
    }

    /** 极短正文条目：无论多少分都只给标题。 */
    private static NewsRow terse(long id, long t) {
        return row(id, fixed("某公司发布公告称事项已完成", TITLE_CHARS), "刚刚。", t);
    }

    /** 补到指定长度，模拟真实标题的平均长度。 */
    private static String fixed(String base, int n) {
        String pad = "关于本次事项的进一步说明与补充材料安排";
        StringBuilder sb = new StringBuilder(base);
        while (sb.length() < n) {
            sb.append(pad);
        }
        return sb.substring(0, n);
    }

    private static String body(int chars) {
        StringBuilder sb = new StringBuilder(chars);
        while (sb.length() < chars) {
            sb.append("公司表示本次事项对经营无重大影响。");
        }
        return sb.substring(0, chars);
    }

    @Test
    @DisplayName("分数达到 3 才给正文，其余只给标题")
    void thresholdSplits() {
        PackResult p = Packer.pipeline(List.of(strong(1, 1000), weak(2, 1100)));

        assertEquals(1, p.l1().size());
        assertEquals(1, p.l2().size());
        assertEquals(1, p.l1().get(0).id());
        assertEquals(2, p.l2().get(0).id());
        assertTrue(p.l1().get(0).score() >= Scorer.L1_THRESHOLD);
        assertTrue(p.l2().get(0).score() < Scorer.L1_THRESHOLD);
        assertTrue(p.l2().get(0).content() == null, "L2 不能带正文");
    }

    @Test
    @DisplayName("L1 超过上限按分数截断，被挤下去的是降级到 L2 而不是丢弃")
    void l1CappedByScore() {
        List<NewsRow> rows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            rows.add(strong(i, 1000 + i));
        }
        for (int i = 101; i <= 120; i++) {
            rows.add(weak(i, 1000 + i));
        }

        PackResult p = Packer.pipeline(rows);

        assertEquals(Packer.MAX_L1, p.l1().size());
        assertEquals(40, p.l2().size(), "20 条本来就低分 + 20 条被降级");
        assertEquals(120, p.candidates(), "降级不丢条目：120 条进，120 条出");
        // 进了 L1 的必须都是高分：最小的那个不能低于没进的那个
        int minL1 = p.l1().stream().mapToInt(PackedNews::score).min().orElse(0);
        int maxL2 = p.l2().stream().mapToInt(PackedNews::score).max().orElse(0);
        assertTrue(minL1 >= maxL2, "截断必须按分数从高到低，实际 minL1=" + minL1 + " maxL2=" + maxL2);
    }

    @Test
    @DisplayName("L2 按时间倒序，读起来像一条时间线")
    void l2SortedByTimeDesc() {
        List<NewsRow> rows = List.of(
                weak(1, 1000),
                weak(2, 3000),
                weak(3, 2000));

        PackResult p = Packer.pipeline(rows);

        assertEquals(List.of(2L, 3L, 1L), p.l2().stream().map(PackedNews::id).toList());
    }

    @Test
    @DisplayName("输入 token 硬闸：超了先降级，降级仍不够才丢弃，最终不越界")
    void hardTokenCap() {
        List<NewsRow> rows = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            rows.add(strong(i, 1000 + i));
        }

        int cap = 2000;
        PackResult p = Packer.pack(Classifier.classify(rows).items(), Packer.MAX_L1, cap);

        assertTrue(p.estTokens() <= cap, "预估 " + p.estTokens() + " 超过硬闸 " + cap);
        assertTrue(p.demoted() > 0, "应先降级（L1→L2），而不是直接丢弃整条新闻");
        assertEquals(0, p.l1().size(), "80 条正文全降完还是超，说明这个 cap 下一条正文都给不起");
        assertTrue(p.dropped() > 0, "2000 token 连 200 条标题都装不下，必须走到丢弃");
        assertEquals(p.fetched(), p.candidates() + p.dropped(), "账要对得上：进来 = 留下 + 丢掉");
    }

    @Test
    @DisplayName("满负荷 600 条仍落在预算内，且真实数字是 2.5 万级而不是文档里的 1.8 万")
    void sixHundredRowsWithinBudget() {
        // 造得接近真实分布：200 条高分（其中 80 条进 L1）、400 条极短正文（强制 L2）
        List<NewsRow> rows = new ArrayList<>(600);
        for (int i = 1; i <= 200; i++) {
            rows.add(strong(i, 1000 + i));
        }
        for (int i = 201; i <= 600; i++) {
            rows.add(terse(i, 1000 + i));
        }

        PackResult p = Packer.pipeline(rows);

        assertEquals(600, p.fetched());
        assertEquals(Packer.MAX_L1, p.l1().size());
        assertEquals(520, p.l2().size());
        assertTrue(p.estTokens() <= Packer.MAX_TOKENS_IN,
                "600 条场景预估 " + p.estTokens() + " 超过硬闸 " + Packer.MAX_TOKENS_IN);
        // 真实数字要看得见。按 28 字标题 + 170 字正文 + 真实行前缀算，600 条是 2.5 万量级；
        // 文档 §7.6 原来只给 8 个字符的行开销，算出来 1.8 万，低估了 40%。
        assertTrue(p.estTokens() > 23_000 && p.estTokens() < 27_000,
                "600 条的分层后预估应在 2.3–2.7 万之间，实际 " + p.estTokens());
        assertTrue(p.digest().contains("80 条含正文"), p.digest());
        assertTrue(p.digest().contains("520 条仅标题"), p.digest());
    }

    @Test
    @DisplayName("正文超长会截断并显式标注，不静默切掉")
    void longContentTruncated() {
        NewsRow huge = row(1, "某公司拟回购2亿元股份", body(3000), 1000);

        PackResult p = Packer.pipeline(List.of(huge));

        String content = p.l1().get(0).content();
        assertTrue(content.length() > Packer.CONTENT_CAP, "截断后要带上标记，长度会略超上限");
        assertTrue(content.endsWith("…[正文已截断]"), "必须让模型知道后面还有内容它没看到");
        assertEquals(NewsClass.Kind.NORMAL, p.l1().get(0).kind());
    }
}
