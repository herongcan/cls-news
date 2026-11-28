package com.herc.clsnews.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.config.AppProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「key 全站只有一份」的契约。
 *
 * <p>这些用例全部来自真实故障，不是假想的边界：
 * <ul>
 *   <li>2026-11-28 验收时为了测主线拒答，只给了 {@code --app.agent.api-key}，
 *       结果点「分析」弹「大模型未就绪」—— 主线是好的，分析没有 key；
 *   <li>反向的错法更隐蔽：两处各配一把不同的 key，谁也不会报错，只在某天
 *       「分析能用、主线 401」时才发现。
 * </ul>
 *
 * <p>所以断言的不是"某个方法返回什么"，而是<b>两个消费方看到的是同一个结果</b>：
 * {@code assertSame} 而不是 {@code assertEquals}，因为共用一份的含义就是同一个对象，
 * 不是两个恰好相等的值。
 */
class LlmKeyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEEPSEEK = "https://api.deepseek.com";

    @TempDir
    Path home;

    private String originalHome;

    /** 把 user.home 指到临时目录：auth.json 兜底那条路要可控，不能读开发机上的真文件。 */
    private void useFakeHome() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (originalHome != null) {
            System.setProperty("user.home", originalHome);
        }
    }

    private static AppProps props(String analysisKey, String agentKey, String baseUrl) {
        AppProps p = new AppProps();
        p.getAnalysis().setApiKey(analysisKey);
        p.getAnalysis().setBaseUrl(baseUrl);
        p.getAgent().setApiKey(agentKey);
        return p;
    }

    @Test
    @DisplayName("只配 analysis.api-key：主线与分析拿到同一个对象")
    void oneKeyServesBoth() {
        useFakeHome();
        LlmKey key = new LlmKey(props("sk-analysis-0123456789", "", DEEPSEEK), MAPPER);

        LlmCredential.Resolved forAnalyzer = key.forBaseUrl(DEEPSEEK);
        LlmCredential.Resolved forAgent = key.forBaseUrl(DEEPSEEK);

        assertNotNull(forAnalyzer);
        assertEquals("sk-analysis-0123456789", forAnalyzer.value());
        // 同一个实例：共用一份不是"两个恰好相等的副本"
        assertSame(forAnalyzer, forAgent);
        assertTrue(key.present());
    }

    @Test
    @DisplayName("只配已废弃的 agent.api-key：分析与主线都能用（这正是 11-28 踩的坑）")
    void legacyAgentKeyStillServesAnalyzer() {
        useFakeHome();
        LlmKey key = new LlmKey(props("", "sk-legacy-0123456789", DEEPSEEK), MAPPER);

        // 修复前：主线有 key、分析为 null，页面点「分析」直接报「大模型未就绪」
        assertNotNull(key.forBaseUrl(DEEPSEEK), "单条分析也必须拿得到这把 key");
        assertEquals("sk-legacy-0123456789", key.forBaseUrl(DEEPSEEK).value());
    }

    @Test
    @DisplayName("两处配了不同的 key：以 analysis 为准，不静默分叉")
    void conflictKeepsAnalysis() {
        useFakeHome();
        LlmKey key = new LlmKey(props("sk-analysis-0123456789", "sk-other-9876543210", DEEPSEEK), MAPPER);

        assertEquals("sk-analysis-0123456789", key.forBaseUrl(DEEPSEEK).value());
    }

    @Test
    @DisplayName("auth.json 里的官方 key 不发给中转站")
    void authFileKeyIsNotSentToRelay() throws Exception {
        // 环境变量优先级高于 auth.json；本机/CI 若导出了它，这条用例测的就不是 auth.json 了
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(LlmCredential.ENV) == null, "环境变量已设置，跳过 auth.json 分支");
        useFakeHome();
        Files.createDirectories(home.resolve(".codex"));
        Files.writeString(home.resolve(".codex/auth.json"),
                "{\"OPENAI_API_KEY\":\"sk-from-codex-0123456789\"}");

        LlmKey key = new LlmKey(props("", "", DEEPSEEK), MAPPER);

        assertNotNull(key.forBaseUrl(DEEPSEEK), "官方地址可以用官方 key");
        // 换了中转站还把这把 key 发过去，等于把官方 key 转发给第三方
        assertNull(key.forBaseUrl("https://relay.example.com/v1"));
    }

    @Test
    @DisplayName("三处都没有：返回 null 而不是抛异常，调用方才有机会降级")
    void noKeyAnywhereDegrades() {
        useFakeHome();
        LlmKey key = new LlmKey(props("", "", DEEPSEEK), MAPPER);

        assertNull(key.forBaseUrl(DEEPSEEK));
        assertFalse(key.present());
        assertEquals("未配置", key.masked());
        assertEquals("未配置", key.source());
    }
}
