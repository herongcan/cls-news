package com.herc.clsnews.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板解析测试。
 *
 * <p>解析器的失败模式很特殊：它不会崩，只会<b>静默给错东西</b>。
 * 比如三个模板共用同一段 system、min-hits 没读到变成 0（等于永不拒答）、
 * keywords 里混进一个空的 `""`（LIKE '%%' 等于不限）。
 * 这些都不会报错，表现是"主线内容和预期的不一样"，排查成本极高。
 * 所以这里对每个字段都做正面断言，而不是只测"能加载"。
 */
class TemplateParseTest {

    private static final String MINIMAL = """
            ## t1
            title: 测试模板
            window: 1d
            min-hits: 2
            ## system
            系统提示词
            ## user
            用户提示词 {{news}}
            """;

    @Test
    @DisplayName("元数据逐字段解析：window 折算、keywords 去括号、brief 默认 true")
    void parsesAllMetadata() {
        AgentTemplates t = AgentTemplates.parse("""
                ## first
                title: 今日热点
                window: 7d
                keywords: [英伟达, NVDA, 黄仁勋]
                min-hits: 3
                expand-to: 3d
                cost-hint: 约 0.4 万 token
                ## system
                S1
                ## user
                U1 {{news}}

                ## second
                title: 无关键词
                brief: false
                window: 12h
                keywords: []
                min-hits: 1
                ## system
                S2
                ## user
                U2
                """);

        assertEquals(2, t.size());

        AgentTemplate first = t.find("first").orElseThrow();
        assertEquals("今日热点", first.title());
        assertEquals(List.of("英伟达", "NVDA", "黄仁勋"), first.keywords());
        assertEquals(3, first.minHits());
        assertEquals(604_800L, first.windowSeconds(), "7d 应是 7×86400");
        assertEquals("3d", first.expandTo());
        assertEquals("约 0.4 万 token", first.costHint());
        assertTrue(first.cacheable(), "brief 不写默认 true");
        assertEquals("S1", first.system());
        assertEquals("U1 {{news}}", first.user());

        AgentTemplate second = t.find("second").orElseThrow();
        assertEquals(List.of(), second.keywords());
        assertFalse(second.cacheable());
        assertEquals(43_200L, second.windowSeconds(), "12h 应是 12×3600");
        assertEquals("S2", second.system(), "system 必须归属到最近的模板，不能串");
    }

    @Test
    @DisplayName("缺 title / min-hits / ## system 都在解析期抛，不留到运行时")
    void rejectsIncompleteTemplate() {
        IllegalStateException noTitle = assertThrows(IllegalStateException.class,
                () -> AgentTemplates.parse(MINIMAL.replace("title: 测试模板", "")));
        assertTrue(noTitle.getMessage().contains("title"), noTitle.getMessage());

        IllegalStateException noHits = assertThrows(IllegalStateException.class,
                () -> AgentTemplates.parse(MINIMAL.replace("min-hits: 2", "")));
        assertTrue(noHits.getMessage().contains("min-hits"), noHits.getMessage());

        IllegalStateException noSystem = assertThrows(IllegalStateException.class,
                () -> AgentTemplates.parse(MINIMAL.replace("## system\n系统提示词\n", "")));
        assertTrue(noSystem.getMessage().contains("system"), noSystem.getMessage());

        IllegalStateException noUser = assertThrows(IllegalStateException.class,
                () -> AgentTemplates.parse(MINIMAL.replace("## user\n用户提示词 {{news}}\n", "")));
        assertTrue(noUser.getMessage().contains("user"), noUser.getMessage());
    }

    @Test
    @DisplayName("非法 window / 重复 id / 空文件一律拒绝，不做兜底默认值")
    void rejectsBadWindowAndDuplicates() {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> AgentTemplates.parse(MINIMAL.replace("window: 1d", "window: 一天")))
                .getMessage().contains("window"));

        assertTrue(assertThrows(IllegalStateException.class,
                () -> AgentTemplates.parse(MINIMAL.replace("window: 1d", "window: 1x")))
                .getMessage().contains("window"));

        assertTrue(assertThrows(IllegalStateException.class,
                () -> AgentTemplates.parse(MINIMAL + MINIMAL.replace("## t1", "## t1")))
                .getMessage().contains("重复"));

        assertThrows(IllegalStateException.class, () -> AgentTemplates.parse("   "));
    }

    @Test
    @DisplayName("真实 templates.md 能加载，且三条防编造的硬约束没被误删")
    void loadsRealFile() {
        AgentTemplates t = AgentTemplates.load("prompts/agent/templates.md");

        assertEquals(3, t.size());
        assertEquals(List.of("hot-today", "nvda-bullish", "a-share-drop"),
                t.all().stream().map(AgentTemplate::id).toList());

        AgentTemplate hot = t.find("hot-today").orElseThrow();
        assertEquals(86_400L, hot.windowSeconds());
        assertEquals(5, hot.minHits());
        assertTrue(hot.keywords().isEmpty(), "今日主线不限关键词，否则会把大部分当天新闻排除在外");
        assertTrue(hot.topic().isEmpty(), "今日主线不做主题过滤：它要的就是当天全部新闻");
        assertTrue(hot.user().contains("{{news}}"), "占位符写错就永远拼不进新闻");
        assertTrue(hot.user().contains("{{digest}}"));
        assertTrue(hot.system().contains("[[id]]"), "引用格式必须写在 system 里");

        AgentTemplate nvda = t.find("nvda-bullish").orElseThrow();
        assertEquals(604_800L, nvda.windowSeconds(), "英伟达是 7 天窗，跨天引用才说得通");
        assertTrue(nvda.keywords().contains("英伟达"));

        AgentTemplate drop = t.find("a-share-drop").orElseThrow();
        assertTrue(drop.system().contains("没有任何指数行情数据"),
                "这条是防止模型编造'A股跌了X%'的唯一约束，删了就等于允许编行情");
        assertEquals(3, drop.minHits());
        assertTrue(drop.topic().contains("沪指"), "主题锚点是这个模板不答非所问的唯一保障");
        assertTrue(drop.topic().contains("创业板"));
        assertEquals(3, drop.minTopicHits());
    }

    @Test
    @DisplayName("写了 min-topic-hits 却没写 topic 是配置错误，解析期就抛")
    void rejectsTopicThresholdWithoutAnchors() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentTemplates.parse(MINIMAL.replace("min-hits: 2", "min-hits: 2\nmin-topic-hits: 3")));

        assertTrue(e.getMessage().contains("min-topic-hits"), e.getMessage());
    }
}
