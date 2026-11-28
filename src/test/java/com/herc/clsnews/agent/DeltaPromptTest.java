package com.herc.clsnews.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量提示词的形状。
 *
 * <p>这里测的不是"字符串拼得对不对"，而是<b>模型能不能看懂它拿到的四段东西</b>：
 * 旧结论、旧标题全貌、旧引用原文、本次新增。少任何一段都会产生一种特定的坏输出——
 * 少 {@code previous} 会写成增量播报，少 {@code previous_titles} 会重复提旧事，
 * 少 {@code previous_evidence} 会把旧结论照抄一遍。
 */
class DeltaPromptTest {

    private static final long T = Instant.parse("2026-11-27T06:32:00Z").getEpochSecond();
    private static final long FROM = Instant.parse("2026-11-26T16:00:00Z").getEpochSecond();
    private static final long TO = FROM + 86_400L;

    private static final String USER = """
            以下是 {{from}} 至 {{to}} 的电报。{{digest}}
            {{previous}}
            <news>
            {{news}}
            </news>
            {{mode}}

            提炼 3–5 条今日主线。
            """;

    private static AgentTemplate tpl() {
        return new AgentTemplate("t", "今日主线", true, "1d", List.of(), 3, List.of(), 0, null,
                "hint", "只输出 JSON", USER);
    }

    /** 30 条：id 1–20 属于上一版，21–30 是新增。正文都够长，不会被判成 TERSE。 */
    private static PackResult pack() {
        List<NewsRow> rows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            rows.add(new NewsRow(i, "第" + i + "条消息标题",
                    "公司公告称，本次事项已经董事会审议通过，具体内容详见公告，实施期限为十二个月，"
                            + "资金来源为自有资金，预计对本年度经营业绩不构成重大影响。",
                    T + i, "B"));
        }
        return Packer.pipeline(rows);
    }

    private static String section(String user, String tag) {
        int a = user.indexOf("<" + tag + ">");
        int b = user.indexOf("</" + tag + ">");
        assertTrue(a >= 0 && b > a, "缺少 <" + tag + "> 段：" + user);
        return user.substring(a, b);
    }

    @Test
    @DisplayName("四段齐全，且新增只出现在 <news> 里")
    void fourSectionsWithCorrectSplit() {
        Answer prev = new Answer("今日主线", List.of(
                new Answer.Bullet("贵金属链集体走强", List.of(1L, 2L))), false);

        PromptBuilder.DeltaPrompt d = PromptBuilder.renderDelta(
                tpl(), pack(), 20, prev, T, 20, FROM, TO, 30_000);

        String user = d.user();
        assertTrue(user.contains("<previous>"), "缺旧结论，模型会写成增量播报");
        assertTrue(user.contains("<previous_titles>"), "缺旧标题全貌，模型不知道当时还有哪些没进主线");
        assertTrue(user.contains("<previous_evidence>"), "缺旧引用原文，模型判断旧结论成立与否只能靠猜");

        // 旧条目的标题在 previous_titles 里
        assertTrue(section(user, "previous_titles").contains("[[1]]"), "旧标题必须全量给到");
        // 新增条目在 <news> 里，旧条目不在
        String news = section(user, "news");
        assertTrue(news.contains("[[21]]"), "新增条目必须出现在 <news> 里");
        assertTrue(!news.contains("[[1]]"), "旧条目不该重复出现在 <news>，否则增量白做");

        assertEquals(2, d.evidenceCount(), "旧结论引用了两条，证据段就该有两条");
        assertTrue(!d.trimmed());
    }

    @Test
    @DisplayName("旧结论按序号列出，引用用 [[]] 标注，和正文行格式一致")
    void previousBlockIsReadable() {
        Answer prev = new Answer("今日主线", List.of(
                new Answer.Bullet("贵金属链集体走强", List.of(1L, 2L)),
                new Answer.Bullet("回购潮延续", List.of(3L))), false);

        String prevSection = section(PromptBuilder.renderDelta(
                tpl(), pack(), 20, prev, T, 20, FROM, TO, 30_000).user(), "previous");

        assertTrue(prevSection.contains("1. 贵金属链集体走强  [[1]]  [[2]]"), prevSection);
        assertTrue(prevSection.contains("2. 回购潮延续  [[3]]"), prevSection);
        assertTrue(prevSection.contains("共 2 条主线"), "要告诉模型上一版有几条，它才知道该不该合并");
    }

    @Test
    @DisplayName("digest 说明总数与新增数，模型才知道 <news> 只是增量")
    void digestExplainsDelta() {
        Answer prev = new Answer("今日主线", List.of(
                new Answer.Bullet("旧结论", List.of(1L))), false);

        String user = PromptBuilder.renderDelta(
                tpl(), pack(), 20, prev, T, 20, FROM, TO, 30_000).user();

        assertTrue(user.contains("今日共 30 条"), user);
        assertTrue(user.contains("本次新增 10 条"), user);
    }

    @Test
    @DisplayName("证据超过 20 条只带前 20 条 —— 否则整份正文又会被塞回来")
    void evidenceCapped() {
        List<Long> refs = new ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            refs.add(i);
        }
        Answer prev = new Answer("今日主线", List.of(new Answer.Bullet("一大条", refs)), false);

        PromptBuilder.DeltaPrompt d = PromptBuilder.renderDelta(
                tpl(), pack(), 20, prev, T, 20, FROM, TO, 30_000);

        assertEquals(PromptBuilder.MAX_EVIDENCE, d.evidenceCount());
        assertTrue(d.trimmed(), "砍过证据就要能看出来，否则成本对不上时无从解释");
    }

    @Test
    @DisplayName("超硬闸时先砍证据、不砍旧标题")
    void overBudgetTrimsEvidenceFirst() {
        Answer prev = new Answer("今日主线", List.of(
                new Answer.Bullet("旧结论", List.of(1L, 2L, 3L, 4L, 5L))), false);

        PromptBuilder.DeltaPrompt d = PromptBuilder.renderDelta(
                tpl(), pack(), 20, prev, T, 20, FROM, TO, 300);

        assertEquals(0, d.evidenceCount(), "硬闸压到很小的时候，证据要能一路砍到 0");
        assertTrue(d.trimmed());
        assertTrue(d.user().contains("<previous_titles>")
                        && section(d.user(), "previous_titles").contains("[[1]]"),
                "旧标题不能砍：砍了模型就不知道自己漏看了什么，会把旧事重新当成新闻提");
    }

    @Test
    @DisplayName("模板缺 {{previous}} 时直接报错，不静默退化成增量播报")
    void missingPlaceholderFailsLoudly() {
        AgentTemplate bad = new AgentTemplate("bad", "x", true, "1d", List.of(), 3, List.of(), 0,
                null, "hint", "sys", "以下是 {{from}} 至 {{to}}。{{digest}}\n<news>{{news}}</news>");
        Answer prev = new Answer("t", List.of(new Answer.Bullet("x", List.of(1L))), false);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PromptBuilder.renderDelta(bad, pack(), 20, prev, T, 20, FROM, TO, 30_000));

        assertTrue(e.getMessage().contains("{{previous}}"), e.getMessage());
    }

    @Test
    @DisplayName("增量带「不是增量列表」的硬指令，全量不带")
    void deltaCarriesModeHint() {
        Answer prev = new Answer("t", List.of(new Answer.Bullet("旧结论", List.of(1L))), false);

        String delta = PromptBuilder.renderDelta(
                tpl(), pack(), 20, prev, T, 20, FROM, TO, 30_000).user();
        String full = PromptBuilder.render(tpl(), pack(), FROM, TO);

        // 缺这句话时真模型会照着 <news> 里的 3 条新增写 3 条主线，旧的 5 条全消失
        assertTrue(delta.contains("不是增量列表"), "这条指令是真模型验收逼出来的，删了就会退回增量播报");
        assertTrue(delta.contains("重新排一遍"), delta);
        assertTrue(!full.contains("不是增量列表"), "全量没有增量这回事，不该出现这句");
    }

    @Test
    @DisplayName("模板有 {{previous}} 但缺 {{mode}} 也要报错 —— 输入结构对、任务描述错，一样写成增量播报")
    void missingModePlaceholderFailsLoudly() {
        AgentTemplate bad = new AgentTemplate("bad", "x", true, "1d", List.of(), 3, List.of(), 0,
                null, "hint", "sys",
                "{{from}}{{to}}{{digest}}\n{{previous}}\n<news>{{news}}</news>");
        Answer prev = new Answer("t", List.of(new Answer.Bullet("x", List.of(1L))), false);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PromptBuilder.renderDelta(bad, pack(), 20, prev, T, 20, FROM, TO, 30_000));

        assertTrue(e.getMessage().contains("{{mode}}"), e.getMessage());
    }

    @Test
    @DisplayName("全量渲染里 {{previous}} 被换成空串，不会留给模型一个没替换的占位符")
    void fullRenderHasNoPlaceholder() {
        String user = PromptBuilder.render(tpl(), pack(), FROM, TO);

        assertTrue(!user.contains("{{previous}}"), "占位符漏进 prompt 会让模型开始猜测它的含义");
        assertTrue(!user.contains("{{"), "还有别的占位符没替换：" + user);
    }
}
