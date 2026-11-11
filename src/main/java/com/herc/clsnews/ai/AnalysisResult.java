package com.herc.clsnews.ai;

import java.util.List;

/**
 * 分析结果。这个结构会原样存进 news.analysis_json，也被前端直接消费，
 * 所以字段名一旦定了就不要随手改 —— 改之前先看 prompts/analyze-news.md。
 *
 * @param sentiment  bullish（利好）/ neutral（中性）/ bearish（利空）
 * @param confidence 0~1，模型自评置信度
 * @param summary    一句话结论，20 字以内
 * @param stocks     涉及的个股，可空
 * @param model      产生该结果的模型标识，便于以后换模型对比
 */
public record AnalysisResult(
        String sentiment,
        double confidence,
        String summary,
        List<StockImpact> stocks,
        String model
) {

    /**
     * @param code   股票代码，如 sz002392
     * @param name   股票名称
     * @param impact bullish / bearish / neutral，针对该股的影响方向
     * @param reason 理由，30 字以内
     */
    public record StockImpact(String code, String name, String impact, String reason) {
    }
}
