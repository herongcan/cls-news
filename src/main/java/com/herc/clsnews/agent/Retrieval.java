package com.herc.clsnews.agent;

import com.herc.clsnews.web.LikePattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 取数：按时间窗（可选关键词）把候选新闻一次性捞进内存。
 *
 * <p><b>为什么不 LIMIT、不排序、不打分在 SQL 里</b>：600 行 × 约 200 字符 ≈ 120KB，
 * 全量进内存毫无压力；而打分、合并、分层这些规则需要可测试、可调、可被产品同学看懂，
 * 埋在 SQL 里就只剩"跑一次看结果"这一种验证方式。
 * 这条边界很清楚：<b>SQL 只负责"哪些条在窗口内"，其余决策全在 Java 里。</b>
 *
 * <p><b>为什么不查 {@code analysis_json}</b>：见 {@link NewsRow} 的说明 ——
 * 「今日主线」只读标题与正文，混读历史分析会让模型权重不一致。
 *
 * <p>关键词转义复用 {@link LikePattern}，不在这里再写一遍：
 * 漏转义的表现是"搜 100% 命中 100 元"这种静默错误，没有任何报错，
 * 两份实现只会多一处出错的地方。
 */
@Component
public class Retrieval {

    /** 关键词上限。模板写 6–8 个同义词是常态，50 个只可能是写错了。 */
    static final int MAX_KEYWORDS = 20;

    private final JdbcTemplate jdbc;

    public Retrieval(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 取时间窗内的新闻，多个关键词之间是 <b>OR</b>。
     *
     * <p>为什么是列表而不是单个字符串：模板的 {@code keywords} 本来就是一组同义词
     * （{@code 暴跌 / 大跌 / 重挫…}），把它们用 AND 串起来结果是 0 条，
     * 用 OR 才是"提到下跌即可"的真实意图。S1.2 之前这个字段解析了却没人用，
     * 于是 {@code a-share-drop} 实际喂给模型的是当天<b>全部</b> 139 条，
     * 模型忠实地挑了两条美股下跌出来 —— 模板的"只分析下跌"从第一步就失效了。
     *
     * @param fromEpoch 起始时间（含），epoch 秒
     * @param toEpoch   结束时间（不含），epoch 秒
     * @param keywords  关键词，null/空表示不限；每个词最长 50 字（和搜索接口口径一致），
     *                  最多取前 {@value #MAX_KEYWORDS} 个，防止模板写错把 SQL 撑爆
     * @return 按发布时间倒序
     */
    public List<NewsRow> fetch(long fromEpoch, long toEpoch, List<String> keywords) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, title, content, publish_time, level
                FROM news
                WHERE publish_time >= ? AND publish_time < ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(fromEpoch);
        args.add(toEpoch);

        List<String> kws = normalize(keywords);
        if (!kws.isEmpty()) {
            // 和搜索接口同一个模式：标题或正文命中即可。ESCAPE 必须写，否则 LikePattern 的转义白做
            sql.append(" AND (");
            for (int i = 0; i < kws.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("title LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\'");
                String pattern = LikePattern.of(kws.get(i));
                args.add(pattern);
                args.add(pattern);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY publish_time DESC, id DESC");

        return jdbc.query(sql.toString(), (rs, i) -> new NewsRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getLong("publish_time"),
                rs.getString("level")), args.toArray());
    }

    /** 去空、去重、截长、限量。空字符串必须在这里被丢掉：{@code LIKE '%%'} 等于不限。 */
    private static List<String> normalize(List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String k : keywords) {
            if (k == null) {
                continue;
            }
            String v = k.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (v.length() > 50) {
                v = v.substring(0, 50);
            }
            if (!out.contains(v)) {
                out.add(v);
            }
            if (out.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return out;
    }

    /**
     * 当前数据水位线 = {@code max(news.id)}。
     *
     * <p>为什么用 id 而不是时间：id 紧凑单调（采集侧用普通 INTEGER PRIMARY KEY，
     * 冲突时会复用未落库的序号），所以"有没有新数据"用 id 比用时间戳准 ——
     * 同一秒进来的两条新闻时间戳相同，但 id 不同。
     *
     * @return 最大 id；空库返回 0
     */
    public long watermark() {
        Long v = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM news", Long.class);
        return v == null ? 0 : v;
    }
}
