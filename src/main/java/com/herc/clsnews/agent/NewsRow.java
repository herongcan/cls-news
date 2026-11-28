package com.herc.clsnews.agent;

/**
 * 检索阶段的一行原始新闻。
 *
 * <p>刻意只带 5 个字段，不是把 {@code news} 表整行搬过来：
 * <ul>
 *   <li>不带 {@code analysis_json} —— 「今日主线」的提示词明确只读标题与正文，
 *       不读历史分析。混读会让模型因为"这条有结论、那条没有"而给出不一致的权重，
 *       而库里此刻还有 1000+ 条 pending，混读的结果是主线质量取决于运气。</li>
 *   <li>不带 {@code stocks_json} —— Step 1 不做个股映射。</li>
 * </ul>
 * 字段少还有一个副作用是好的：19 行记录的拷贝成本可以忽略，
 * 所以整个流水线都是"生成新列表"而不是"就地改"，不会出现上游被下游改脏的问题。
 *
 * @param id          新闻 id，最终要作为引用（{@code [[id]]}）的依据
 * @param title       标题
 * @param content     正文，可能为空串
 * @param publishTime 发布时间，epoch 秒
 * @param level       来源级别 A/B/C，实测 A 级为 0 条，仅留档
 */
public record NewsRow(
        long id,
        String title,
        String content,
        long publishTime,
        String level) {

    /** 正文按需截断，避免把 5000 字的深度稿整段塞进 prompt。 */
    public String content(int cap) {
        String c = content == null ? "" : content;
        return c.length() <= cap ? c : c.substring(0, cap);
    }

    /** 实际正文长度（null 按 0 算），用于 token 预估。 */
    public int contentChars() {
        return content == null ? 0 : content.length();
    }

    /** 标题长度，用于 token 预估。 */
    public int titleChars() {
        return title == null ? 0 : title.length();
    }
}
