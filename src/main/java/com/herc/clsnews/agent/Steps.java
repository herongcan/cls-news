package com.herc.clsnews.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 把流水线的计数器翻译成给人看的几行。
 *
 * <p><b>为什么单独成类而不是散在 Service 里</b>：这些文案有一个共同约束 ——
 * 短、是数字、不含配置项名。散在编排代码里，下一次改动很容易顺手拼一句
 * 「因为新增未达重算阈值（≥3）」进来，而那种句子在浮层里会折两行。
 * 集中在这里，{@link StepsTest} 才能把约束写成断言。
 *
 * <p><b>为什么可以少几行</b>：没有剔除就不显示「剔除导流 0 条」。
 * 过程区的读者是「怀疑结论怎么来的」的人，一行恒为 0 的噪音只会让他少看两行。
 */
public final class Steps {

    /** 浮层里 label 一列的可用宽度，按 11.5px 算约 6 个字 */
    public static final int MAX_LABEL = 6;
    /** value 一列更窄，超过就会把 hint 挤没 */
    public static final int MAX_VALUE = 10;

    private Steps() {
    }

    /** 取数 → 剔除 → 合并 → 打包，四步全部来自 {@link PackResult} 的计数器。 */
    public static List<Step> fromScan(PackResult p) {
        List<Step> out = new ArrayList<>(4);
        out.add(Step.of("scan", "取数", p.fetched() + " 条"));
        if (p.promoDropped() > 0) {
            out.add(Step.of("filter", "剔除导流", p.promoDropped() + " 条"));
        }
        if (p.quoteMerged() > 0) {
            out.add(Step.of("merge", "合并播报", p.quoteMerged() + " 条"));
        }
        out.add(new Step("pack", "打包", p.candidates() + " 条",
                p.trimmed()
                        ? "超预算，降级 " + p.demoted() + " 条、丢弃 " + p.dropped() + " 条"
                        : "含正文 " + p.l1().size() + " 条，仅标题 " + p.l2().size() + " 条"));
        return out;
    }

    /** 材料校验的结论。拒答时把 detail 放进 hint —— 那句话正是用户要的答案。 */
    public static Step guard(Guard.Verdict v) {
        return v.rejected()
                ? new Step("guard", "材料校验", "不足", v.detail())
                : Step.of("guard", "材料校验", "通过");
    }

    /**
     * 模型那一步的占位行。发出请求时先推它，界面上「正在等」才有落点。
     *
     * <p>value 用「等待中」而不是空串：浮层那一列是定宽的，空值会让整行看起来像没对齐；
     * 而且空串在 JSON 里和 null 难以区分，前端得写两套判断。
     *
     * <p>它一定会被 {@link #llm} 或 {@link #llmFailed} 覆盖（{@link Proc#replace}），
     * 所以用户看到的最终版里不会留着这三个字。
     */
    public static Step llmWaiting() {
        return new Step("llm", "调用模型", "等待中", null);
    }

    /** @param tokensIn 输入 token，放在 hint 里；它比耗时更能解释「这次为什么贵」 */
    public static Step llm(long latencyMs, int tokensIn) {
        return new Step("llm", "调用模型", oneDecimal(latencyMs / 1000.0) + " 秒", tokensIn + " token");
    }

    /**
     * 模型没回来。
     *
     * <p>这一行是把「卡在哪」和「卡了多久」分开的关键：没有它，失败路径上最后一次
     * 推送停在「等待中」，用户永远不知道是等了 3 秒就 401，还是等满 120 秒超时。
     *
     * @param why 简短原因（HTTP 401 / 超时 / 解析失败），写进 hint，不占 value 那一列
     */
    public static Step llmFailed(long latencyMs, String why) {
        return new Step("llm", "调用模型", "失败 " + oneDecimal(latencyMs / 1000.0) + " 秒", why);
    }

    /**
     * 引用校验剔掉了几个。
     *
     * <p>这条是唯一能让人确认「结论没有编」的地方：模型给 20 个 id，剔掉 2 个，
     * 说明有 2 条引用的原文并不在候选集里 —— 它们被剥掉了，没有留在界面上。
     */
    public static Step cite(AnswerParser.Result r) {
        return r.droppedRefs() > 0
                ? new Step("cite", "引用校验", "剔掉 " + r.droppedRefs() + " 个", "不在候选集内")
                : Step.of("cite", "引用校验", "全部有效");
    }

    /**
     * 模型的思考过程，落库用。
     *
     * <p>注意它和 {@code llm} 那一步的关系：{@code llm} 记的是「调用模型花了多久」，
     * 这一行记的是「模型想了什么」。思考全文塞进 {@code hint}（纯文本，不掺别的），
     * 前端把它抽成「思考」卡片展示，value 只作占位不展示。
     *
     * @param reasoningText 思考全文，空/null 时不产生这一行（没要到思考就不写，别留空行）
     */
    public static Step reasoning(String reasoningText) {
        if (reasoningText == null || reasoningText.isBlank()) {
            return null;
        }
        return new Step("reasoning", "思考", "已思考", reasoningText.trim());
    }

    private static String oneDecimal(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }
}
