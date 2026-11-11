package com.herc.clsnews.model;

/**
 * 数据源返回的原始条目，尚未落库。来源适配层只负责产出这个结构，
 * 换接口（cls 接口 / RSSHub / mock）时不用动下游任何代码。
 */
public record RawItem(
        String itemId,
        String title,
        String content,
        String url,
        long publishTime,
        String level,
        String stocksJson
) {
}
