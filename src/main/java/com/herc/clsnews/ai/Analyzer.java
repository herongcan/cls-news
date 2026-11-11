package com.herc.clsnews.ai;

import com.herc.clsnews.model.News;

/**
 * 大模型分析入口 —— 这就是「预留」的全部内容。
 *
 * 接模型时你只需要做两件事：
 *   1. 写一个实现类，例如 DeepSeekAnalyzer，套上 @Component + @ConditionalOnProperty(provider=deepseek)；
 *   2. 把 application.yml 里 app.analysis.provider 改成 deepseek，enabled 改成 true。
 *
 * 提示词和输出结构已经定死在 src/main/resources/prompts/analyze-news.md，
 * 前端也按这个结构渲染，所以实现侧不需要再和任何地方对齐。
 */
public interface Analyzer {

    /** 失败就抛异常，调用方会标记为 failed 并跳过，不影响采集主流程。 */
    AnalysisResult analyze(News news) throws Exception;

    String modelName();

    /** 用量摘要，给 /api/status 看「今天烧了多少 token」。空实现返回空串。 */
    default String usage() {
        return "";
    }

    /**
     * 是否真的能干活（比如 key 是否配好）。
     * 部署后第一眼看 /api/status 的 analysisReady 就知道分析有没有生效，
     * 不必等一条新闻变成 failed 才发现。
     */
    default boolean ready() {
        return true;
    }
}
