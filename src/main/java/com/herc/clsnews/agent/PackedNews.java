package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;

/**
 * 打包后准备进 prompt 的一条。
 *
 * @param id          新闻 id，最终要作为引用依据
 * @param title       展示标题（被合并过的带"（当日 N 次播报）"）
 * @param publishTime 发布时间，epoch 秒
 * @param content     正文；只有 {@code body=true} 时非 null（已按上限截断）
 * @param kind        路由类别，仅用于统计与排查
 * @param score       打分结果
 * @param body        是否给了正文（L1）
 */
public record PackedNews(
        long id,
        String title,
        long publishTime,
        String content,
        NewsClass.Kind kind,
        int score,
        boolean body) {

    /**
     * 这一条在 prompt 里占的字符数。
     *
     * <p>口径必须和 {@link PromptBuilder#line} 实际拼出来的字符串<b>严格相等</b>，
     * 由 {@code PromptBuildTest} 断言。两处各算一套的话，硬闸就形同虚设 ——
     * 预估少算 40% 时，闸门根本没拦住任何东西。
     *
     * <p>S1.2 复核出的关键一点：前缀长度是 <b>17 + id 位数</b>，不是常数 17。
     * 17 只是 id 之外的部分（{@code [[}2 + {@code ]]}2 + 空格1 + 时间11 + 空格1），
     * 漏掉 id 位数后 600 条会少算约 1400 token。
     */
    public int promptChars() {
        int prefix = Packer.PREFIX_CHARS + PromptBuilder.digits(id);
        int titleChars = title == null ? 0 : title.length();
        int tail = body && content != null
                ? Packer.BODY_SEP_CHARS + content.length()
                : Packer.TITLE_SEP_CHARS;
        return prefix + titleChars + tail;
    }
}
