package com.herc.clsnews.ai;

import java.util.List;

/**
 * 分析结果。这个结构会原样存进 news.analysis_json，也被前端直接消费，
 * 所以字段名一旦定了就不要随手改 —— 改之前先看 prompts/analyze-news.md。
 *
 * 2026-11 起从「只判 A 股」扩到 A 股 / 港股 / 美股三市场，同时加了推导与板块字段。
 * 老数据（134 条）没有 inference / sectors / market，反序列化后是 null，
 * 前端一律用 `a.sectors || []` 兜底，不需要回填历史数据。
 *
 * @param sentiment  bullish（利好）/ neutral（中性）/ bearish（利空）
 * @param confidence 0~1，模型自评置信度。注意它表示「原文信息量」，不是判对概率
 * @param summary    一句话结论：什么事 + 意味着什么。中性也要说清为什么中性
 * @param inference  方向靠映射推导得来时的推导链；原文已明确则空串
 * @param sectors    涉及的板块，可空
 * @param stocks     涉及的个股，可空
 * @param model      产生该结果的模型标识，便于以后换模型对比
 */
public record AnalysisResult(
        String sentiment,
        double confidence,
        String summary,
        String inference,
        List<String> sectors,
        List<StockImpact> stocks,
        String model
) {

    /**
     * @param market A股 / 港股 / 美股，由 normalize() 按代码前缀回填
     * @param code   股票代码：A股 sz002392、港股 hk00700、美股 NVDA；不确定时为空串
     * @param name   股票名称
     * @param impact bullish / bearish / neutral，针对该股的影响方向
     * @param reason 理由，30 字以内，只能引用原文
     */
    public record StockImpact(String market, String code, String name, String impact, String reason) {
    }
}
