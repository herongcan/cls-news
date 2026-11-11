package com.herc.clsnews.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 给前端的视图对象。text 会按 app.display.fulltext 决定是全文还是摘要，
 * analysis 为空表示尚未分析（或没接大模型）。
 */
public record NewsView(
        long id,
        long time,
        String level,
        String title,
        String text,
        String url,
        List<Map<String, String>> stocks,
        JsonNode analysis,
        String analysisStatus
) {
}
