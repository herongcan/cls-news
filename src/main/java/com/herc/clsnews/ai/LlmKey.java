package com.herc.clsnews.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.config.AppProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 全站唯一的那把模型 key：启动时解析一次，单条分析与今日主线共用同一个结果。
 *
 * <p><b>为什么必须只解析一次</b>：{@code DeepSeekAnalyzer} 和 {@code DeepSeekAgentLlm}
 * 原本各调一次 {@link LlmCredential#find}，各自传自己的配置项。于是同一把 key 有了两个入口，
 * 而两种错法长得完全不一样：
 * <ul>
 *   <li>只配 {@code app.agent.api-key} → 主线好、单条分析拿到 null，点「分析」直接跳出
 *       「大模型未就绪」；</li>
 *   <li>只配 {@code app.analysis.api-key} → 两处都好。</li>
 * </ul>
 * 同一个人按同一套心智模型操作，结果一半对一半不对，这比两边都坏更难查。
 * 现在解析只发生在这里，两个消费方拿到的是同一个对象，不可能一半有一半没有。
 *
 * <p><b>{@code app.agent.api-key} 没有直接删掉</b>：它是已经写在部署脚本和 .env 里的字段，
 * 直接让 Spring 忽略等于"线上突然说没 key"，而那时人正在看页面、不会去看启动日志。
 * 保留为迁移别名 —— 只配它仍然能用，同时打一条 WARN 让人挪走；两处都配且不一致时
 * 以 analysis 为准并告警。静默分叉是这里最贵的错：它会表现成「分析能用、主线 401」，
 * 而两份代码看起来都对。
 */
@Component
public class LlmKey {

    private static final Logger log = LoggerFactory.getLogger(LlmKey.class);

    /** 解析结果。null 表示三处都没有 key。 */
    private final LlmCredential.Resolved resolved;

    public LlmKey(AppProps props, ObjectMapper mapper) {
        AppProps.Analysis ana = props.getAnalysis();
        AppProps.Agent agent = props.getAgent();

        String anaKey = ana.getApiKey() == null ? "" : ana.getApiKey().trim();
        String agentKey = agent.getApiKey() == null ? "" : agent.getApiKey().trim();
        String site = anaKey.isBlank() ? agentKey : anaKey;

        if (anaKey.isBlank() && !agentKey.isBlank()) {
            log.warn("app.agent.api-key 已废弃：key 现在全站共用一份，请挪到 app.analysis.api-key。"
                    + "本次仍按旧字段生效 —— 但下次换 key 时只改 analysis，主线会跟着一起变，这是预期行为");
        } else if (!anaKey.isBlank() && !agentKey.isBlank() && !anaKey.equals(agentKey)) {
            log.warn("app.analysis.api-key 与 app.agent.api-key 不一致（{} vs {}）：只保留 analysis 那份，"
                            + "agent 那份被忽略。两把 key 并存会造出「分析能用、主线 401」这种两边看起来都对的问题",
                    LlmCredential.mask(anaKey), LlmCredential.mask(agentKey));
        }

        // 用 analysis 的 base-url 解析：它是站点级主配置，auth.json 那条兜底规则也以它为准
        // （换中转站还去读官方 auth.json，等于把官方 key 发给第三方）
        this.resolved = LlmCredential.find(site, ana.getBaseUrl(), mapper);

        if (resolved == null) {
            // provider=stub 时根本不会发请求，缺 key 不是故障，不该用 ERROR 吓人
            if ("deepseek".equalsIgnoreCase(ana.getProvider())) {
                log.error("模型 key 未配置（单条分析与今日主线都不可用，采集照常）：{}",
                        LlmCredential.hint().lines().findFirst().orElse(""));
            } else {
                log.info("provider={} 不需要 key，跳过", ana.getProvider());
            }
        } else {
            log.info("模型 key 就绪：{}（来自 {}），单条分析与今日主线共用",
                    LlmCredential.mask(resolved.value()), resolved.source());
        }
    }

    /**
     * 取某个 base-url 可用的 key。
     *
     * <p>共用一份不等于处处可用，只有一种情况会"有 key 但拒绝发"：这把 key 是从
     * {@code ~/.codex/auth.json} 兜底读到的，而调用方要打的是非 deepseek 的地址（中转站）。
     * 那时返回 null 而不是把官方 key 发过去 —— 泄露比 401 更难收场。
     *
     * @param baseUrl 调用方实际要请求的地址
     * @return null 表示这个地址不能用这把 key
     */
    public LlmCredential.Resolved forBaseUrl(String baseUrl) {
        if (resolved == null) {
            return null;
        }
        boolean fromAuthFile = resolved.source().endsWith("auth.json");
        if (fromAuthFile && (baseUrl == null || !baseUrl.contains("deepseek"))) {
            log.warn("key 来自 auth.json，但 base-url={} 不是 deepseek 官方，拒绝发送（防止官方 key 被转发给第三方）",
                    baseUrl);
            return null;
        }
        return resolved;
    }

    /** 给启动日志与体检用：key 到底有没有。 */
    public boolean present() {
        return resolved != null;
    }

    /** 只露头尾的展示值，供日志与体检复用。 */
    public String masked() {
        return resolved == null ? "未配置" : LlmCredential.mask(resolved.value());
    }

    /** key 从哪来的，排查"为什么这个环境有 key 那个没有"时是第一条线索。 */
    public String source() {
        return resolved == null ? "未配置" : resolved.source();
    }
}
