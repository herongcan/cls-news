package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒答判定的测试。
 *
 * <p>这段代码的每条分支都直接对应"花不花一万 token"，所以断言不只看
 * {@code rejected} 这个布尔值，还要看 <b>reason 是哪一个</b>：
 * 用户看到"今天没有新闻"和看到"有 3 条提到下跌、但没有一条是 A 股"，
 * 该做的下一步完全不同（等一等 vs 换模板）。
 */
class GuardTest {

    /** 用真实解析器造模板，而不是手写构造函数：主题词是配置出来的，测试就该走那条路径。 */
    private static AgentTemplate template(String topicLine) {
        return AgentTemplates.parse("""
                ## t
                title: A 股下跌归因
                window: 1d
                keywords: [暴跌, 大跌]
                min-hits: 3
                %s
                ## system
                S
                ## user
                U {{news}}
                """.formatted(topicLine)).find("t").orElseThrow();
    }

    /** 造 n 条候选；{@code withAnchor} 为 true 的条目在标题里带上"沪指"。 */
    private static PackResult pack(int total, int withAnchor) {
        List<PackedNews> items = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String title = i < withAnchor
                    ? "沪指午后跌幅扩大至 1.2%，成交额萎缩"
                    : "土耳其主要银行指数扩大跌幅，现跌 3.2%";
            items.add(new PackedNews(100 + i, title, 1_700_000_000L + i,
                    "正文与主题无关。", NewsClass.Kind.NORMAL, 3, false));
        }
        return new PackResult(items, List.of(), total, 0, Map.of(), 0, 5000, 30000, 0, 0);
    }

    @Test
    @DisplayName("没配 topic 的模板完全不受主题校验影响（hot-today 走这条）")
    void noTopicMeansNoFilter() {
        Guard.Verdict v = Guard.check(template(""), pack(6, 0));

        assertFalse(v.rejected());
    }

    @Test
    @DisplayName("锚点命中达到下限就放行")
    void passesWhenTopicHitsEnough() {
        Guard.Verdict v = Guard.check(template("topic: [沪指, 创业板, 北向]\nmin-topic-hits: 3"),
                pack(6, 3));

        assertFalse(v.rejected());
    }

    @Test
    @DisplayName("材料够多但主题不符：判 off-topic，理由里带真实计数，用户能判断该换模板")
    void rejectsOffTopicWithCounts() {
        Guard.Verdict v = Guard.check(template("topic: [沪指, 创业板, 北向]\nmin-topic-hits: 3"),
                pack(6, 1));

        assertTrue(v.rejected());
        assertEquals("off-topic", v.reason());
        // 这句话是给用户看的，三个数字缺一不可：命中多少、相关多少、需要多少
        assertTrue(v.detail().contains("6 条"), v.detail());
        assertTrue(v.detail().contains("只有 1 条"), v.detail());
        assertTrue(v.detail().contains("至少需要 3 条"), v.detail());
        assertTrue(v.detail().contains("未调用模型"), v.detail());
    }

    @Test
    @DisplayName("只写 topic 不写下限时，下限回落到 min-hits，而不是 1")
    void minTopicHitsDefaultsToMinHits() {
        AgentTemplate t = template("topic: [沪指]");

        assertEquals(3, t.minTopicHits(), "默认取 min-hits；取 1 挡不住'169 条里只有 1 条是 A 股'");
    }

    @Test
    @DisplayName("材料总量不足时先报 min-hits，不要用主题不符盖住'今天新闻就少'这个事实")
    void materialShortageWinsOverTopic() {
        Guard.Verdict v = Guard.check(template("topic: [沪指]\nmin-topic-hits: 5"), pack(2, 0));

        assertTrue(v.rejected());
        assertEquals("min-hits", v.reason());
    }

    @Test
    @DisplayName("一条里同时出现多个锚点只算一条命中")
    void countsItemsNotAnchorOccurrences() {
        List<PackedNews> items = List.of(new PackedNews(1, "沪指、上证、深证、创业板齐跌",
                1_700_000_000L, "北向资金净流出，两市成交萎缩", NewsClass.Kind.NORMAL, 3, true));

        assertEquals(1, Guard.countTopic(items, List.of("沪指", "上证", "深证", "创业板", "北向")));
    }

    @Test
    @DisplayName("正文里的锚点也算命中：很多电报标题只写'XX板块走弱'，A 股线索在正文里")
    void countsAnchorsInBody() {
        List<PackedNews> items = List.of(new PackedNews(1, "新能源车链走弱",
                1_700_000_000L, "受此影响，创业板指相关权重股承压。", NewsClass.Kind.NORMAL, 3, true));

        assertEquals(1, Guard.countTopic(items, List.of("创业板")));
    }
}
