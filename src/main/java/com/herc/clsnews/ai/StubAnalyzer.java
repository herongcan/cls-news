package com.herc.clsnews.ai;

import com.herc.clsnews.model.News;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 空实现：接口、表字段、调度器、提示词都齐了，只差一次模型调用。
 * 手动点「分析」时会明确告诉你还缺什么，而不是假装成功。
 */
@Component
@ConditionalOnProperty(prefix = "app.analysis", name = "provider", havingValue = "stub", matchIfMissing = true)
public class StubAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(StubAnalyzer.class);

    @Override
    public AnalysisResult analyze(News news) {
        log.debug("StubAnalyzer 被调用，news.id={}", news.id());
        throw new UnsupportedOperationException(
                "大模型尚未接入：实现 com.herc.clsnews.ai.Analyzer，"
                        + "并在 application.yml 中设置 app.analysis.provider 与 enabled=true");
    }

    @Override
    public String modelName() {
        return "stub";
    }
}
