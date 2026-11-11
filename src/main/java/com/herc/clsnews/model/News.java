package com.herc.clsnews.model;

/** news 表的一行。字段和 schema.sql 一一对应。 */
public record News(
        long id,
        String itemId,
        String title,
        String content,
        String url,
        long publishTime,
        String level,
        String stocksJson,
        long createdAt,
        String analysisStatus,
        String analysisJson,
        Long analyzedAt,
        String model
) {
}
