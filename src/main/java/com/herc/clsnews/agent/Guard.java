package com.herc.clsnews.agent;

import java.util.List;

/**
 * 生成前的拒答判定：材料不够就<b>不调模型</b>。
 *
 * <p>这是整个方案里最省钱的一段代码。一次主线调用约 1 万 token，
 * 而它回答的是"今天的主线是什么"——如果今天只有 2 条电报，这个问题没有答案，
 * 模型也只能写"今日信息较少，市场情绪平稳"这种正确的废话。
 * 花一万 token 买一句废话，比报错更糟：用户会以为系统在工作。
 *
 * <p>所以判定必须在<b>调用之前</b>，且判据要能被用户理解（"近 1 天仅 2 条命中，
 * 不足以提炼主线（需要至少 5 条）"），而不是一句"材料不足"。
 *
 * <p>关于 {@link AgentTemplate#expandTo()}：模板里写了"不足时允许扩到 3 天"，
 * <b>S1.2 只解析不生效</b>。自动扩窗会让"取数窗口"和"用户以为的窗口"不一致
 * （用户点的是「今日」，结果混进了前天的新闻），这种偏差比拒答更难解释。
 * 真要扩窗，应该由界面上一个明确的选项触发，而不是偷偷扩 —— 那件事留到 S1.4。
 *
 * <h2>主题校验（{@code topic} / {@code min-topic-hits}）</h2>
 * 关键词检索是字面匹配，"跌"字会把美股个股、土耳其银行、DRAM 合约价一起捞进来。
 * {@code a-share-drop} 实测就踩过：139 条候选里只有 1 条真属于 A 股，
 * 模型忠实地引用了两条美股下跌 —— 措辞没错，答非所问，花掉 1.1 万 token。
 * 主题校验用一组锚点词（沪指/创业板/北向…）数"有多少条真落在主题内"，
 * 不够就拒答。它不判断语义，只数锚点，所以便宜、可解释、可单测。
 */
public final class Guard {

    private Guard() {
    }

    /**
     * @param rejected 是否应当拒答
     * @param reason   机器码，写进 {@code agent_run.error}
     * @param detail   给用户看的一句话
     */
    public record Verdict(boolean rejected, String reason, String detail) {

        public static Verdict pass() {
            return new Verdict(false, null, null);
        }

        public static Verdict reject(String reason, String detail) {
            return new Verdict(true, reason, detail);
        }
    }

    /**
     * 判定。
     *
     * <p>三条判据的顺序有讲究：先判"有没有料"，再判"料够不够多"，最后判"料对不对题"。
     * 反过来会把"今天一条新闻都没有"说成"和 A 股无关"，用户无从判断该等一等还是换模板。
     *
     * @param t    模板（提供 min-hits、主题锚点与窗口描述）
     * @param pack 打包结果（提供候选条目）
     */
    public static Verdict check(AgentTemplate t, PackResult pack) {
        int candidates = pack == null ? 0 : pack.candidates();

        if (candidates == 0) {
            return Verdict.reject("no-data",
                    "近 " + t.window() + " 没有采集到新闻，无法提炼主线。等采集到新内容后再试。");
        }
        if (candidates < t.minHits()) {
            return Verdict.reject("min-hits",
                    "近 " + t.window() + " 仅 " + candidates + " 条相关新闻，不足以提炼主线（至少需要 "
                            + t.minHits() + " 条）。可以换个时间窗更长的模板，或等采集到更多内容。");
        }
        if (!t.topic().isEmpty()) {
            int topicHits = countTopic(pack.all(), t.topic());
            if (topicHits < t.minTopicHits()) {
                return Verdict.reject("off-topic", offTopicDetail(t, candidates, topicHits));
            }
        }
        return Verdict.pass();
    }

    /**
     * 候选里有多少条真的落在主题内。
     *
     * <p>判据只看标题与正文是否出现锚点词（沪指/创业板/北向…），不做语义判断 ——
     * 这一层的价值在于<b>便宜且可解释</b>：拒答时能告诉用户"12 条里只有 1 条提到沪指"，
     * 而不是一句"相关性不足"。
     */
    static int countTopic(List<PackedNews> items, List<String> anchors) {
        if (items == null || items.isEmpty() || anchors == null || anchors.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (PackedNews p : items) {
            String title = p.title() == null ? "" : p.title();
            String content = p.content() == null ? "" : p.content();
            for (String a : anchors) {
                if (a == null || a.isBlank()) {
                    continue;
                }
                if (title.contains(a) || content.contains(a)) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    private static String offTopicDetail(AgentTemplate t, int candidates, int topicHits) {
        // 锚点只列前 4 个：全列出来会把这句话变成一堵墙，用户看不出重点
        String shown = String.join("/", t.topic().subList(0, Math.min(4, t.topic().size())));
        return "近 " + t.window() + " 命中 " + candidates + " 条，但其中只有 " + topicHits
                + " 条与「" + t.title() + "」直接相关（判据：提到 " + shown
                + " 等），至少需要 " + t.minTopicHits()
                + " 条。其余是境外个股或商品行情，硬答会答非所问，因此未调用模型。";
    }
}
