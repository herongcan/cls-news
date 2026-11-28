package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 分层打包：决定谁拿正文、谁只给标题、以及整体不超过多少 token。
 *
 * <p>核心事实来自实测：<b>标题平均 28 字、正文平均 170 字，标题只占全文 14%</b>。
 * 所以"600 条装不装得下"的正确答案不是"装得下就全装"——
 * 满负荷 600 条全部给正文是 11.9 万字符、约 7 万 token，虽然 128k 窗口放得下，
 * 但同时踩三个坑：成本 ×6、首字节 5–15 秒、以及最要紧的<b>质量下滑</b>
 * （600 条里真正有信息的可能只有 60 条，注意力被稀释后主线会退化成"市场情绪回暖"这种正确的废话）。
 *
 * <p>分层后满负荷压到 1.8 万 token。结论那句话值得记住：
 * <b>能不能装下是窗口问题，该不该装是质量问题。</b>
 */
public final class Packer {

    /** L1 上限。80 条 × 平均 200 字 ≈ 1.6 万字符 ≈ 0.9 万 token，是"够用且不稀释"的经验值。 */
    public static final int MAX_L1 = 80;

    /** 输入 token 硬闸。600 条满负荷分层后约 1.8 万，留出余量给模板与系统提示词。 */
    public static final int MAX_TOKENS_IN = 30_000;

    /** 单条正文上限。超过的基本是深度稿，摘要式主线用不到全文。 */
    public static final int CONTENT_CAP = 1200;

    /**
     * 每行的固定前缀里、<b>id 之外</b>的那部分：{@code "[[…]] 11-27 14:32 "} 去掉 id。
     *
     * <p>拆解：{@code [[} 2 + {@code ]]} 2 + 空格 1 + 时间 11 + 空格 1 = 17。
     * 实际长度还要加 id 的位数（见 {@link PackedNews#promptChars()}）。
     *
     * <p><b>S1.2 复核结论（2026-11-27）：</b>原设计文档 §7.6 只给 8 个字符的行开销，
     * 按 8 算 600 条是 1.8 万 token；真实前缀 21 字符（id 4 位）算出来是 2.5 万。
     * 差 40%。这个常数当时定 17 是"宁可高估"，方向对但口径错 ——
     * 17 只覆盖了 id 之外的部分，id 位数被漏掉。
     * 现在由 {@link PromptBuilder} 与 {@code PromptBuildTest} 钉死，两边长度必须严格相等。
     */
    static final int PREFIX_CHARS = 17;

    /** L1 条目：标题后的换行 + 正文后的换行。 */
    static final int BODY_SEP_CHARS = 2;

    /** 仅标题的条目：标题后只有一个换行。 */
    static final int TITLE_SEP_CHARS = 1;

    /**
     * 中文实测折算系数：约 1.7 字 / token。
     *
     * <p>用它而不是调 tokenizer：预估值的用途是"给用户看个量级"和"当硬闸"，
     * 误差 ±10% 完全够用；引入一个 tokenizer 依赖只为更精确，不划算。
     */
    static final double CHARS_PER_TOKEN = 1.7;

    /** 系统提示词 + 模板正文 + 那行分层声明，约 400 token。 */
    static final int PROMPT_OVERHEAD = 400;

    private Packer() {
    }

    /** 用默认参数打包。 */
    public static PackResult pack(List<Classified> items) {
        return pack(items, MAX_L1, MAX_TOKENS_IN);
    }

    /**
     * 打包。
     *
     * @param items       分类合并之后的候选
     * @param maxL1       L1 条数上限
     * @param maxTokensIn 输入 token 硬闸
     * @return 打包结果
     */
    public static PackResult pack(List<Classified> items, int maxL1, int maxTokensIn) {
        List<Classified> input = items == null ? List.of() : items;

        List<PackedNews> l1 = new ArrayList<>();
        List<PackedNews> l2 = new ArrayList<>();
        for (Classified c : input) {
            NewsRow row = c.row();
            int score = Scorer.score(row);
            boolean body = Scorer.eligibleForBody(c.kind(), score);
            PackedNews p = body
                    ? new PackedNews(row.id(), c.displayTitle(), row.publishTime(), body(row), c.kind(), score, true)
                    : new PackedNews(row.id(), c.displayTitle(), row.publishTime(), null, c.kind(), score, false);
            if (body) {
                l1.add(p);
            } else {
                l2.add(p);
            }
        }

        // L1 按分数排，同分取更新的；L2 只按时间排 —— 只给标题的条目已经没有分数可言，
        // 时间倒序是唯一有意义的顺序（读起来像一条时间线）。
        l1.sort(Comparator.comparingInt(PackedNews::score).reversed()
                .thenComparing(Comparator.comparingLong(PackedNews::publishTime).reversed())
                .thenComparing(Comparator.comparingLong(PackedNews::id).reversed()));
        if (l1.size() > maxL1) {
            // 超出上限的直接降到 L2，不是丢掉：一条 60 分的新闻只给标题，仍然比完全看不到好
            for (PackedNews p : l1.subList(maxL1, l1.size())) {
                l2.add(withoutBody(p));
            }
            l1 = new ArrayList<>(l1.subList(0, maxL1));
        }
        sortL2(l2);

        // 硬闸：先降级，再丢弃。
        // 降级优先于丢弃是有意的 —— 同样 300 字的预算，换成 8 条只有标题的新闻，
        // 对"今天发生了什么"这个问题的价值远高于多保留 1 条完整正文。
        int demoted = 0;
        int dropped = 0;
        int est = estimate(l1, l2);
        while (est > maxTokensIn && !l1.isEmpty()) {
            PackedNews last = l1.remove(l1.size() - 1);
            l2.add(withoutBody(last));
            demoted++;
            sortL2(l2);
            est = estimate(l1, l2);
        }
        while (est > maxTokensIn && !l2.isEmpty()) {
            // L2 已按时间倒序，砍最早的那条
            l2.remove(l2.size() - 1);
            dropped++;
            est = estimate(l1, l2);
        }

        return new PackResult(l1, l2, input.size(), 0, Map.of(), 0, est, maxTokensIn, demoted, dropped);
    }

    /**
     * 把分类阶段的统计并进打包结果。
     *
     * <p>分类和打包是两个纯函数，但对外只该暴露一个结果 —— 否则每个调用方都要自己拼
     * {@code PackResult}，拼漏一个字段就会让日志少一个维度。
     */
    public static PackResult withClassify(PackResult packed, Classifier.Result cls) {
        return new PackResult(packed.l1(), packed.l2(), cls.fetched(), cls.promoDropped(),
                cls.promoByTag(), cls.quoteMerged(), packed.estTokens(), packed.maxTokensIn(),
                packed.demoted(), packed.dropped());
    }

    /** 一步到位：检索结果 → 分类合并 → 打分打包。S1.1 的完整流水线。 */
    public static PackResult pipeline(List<NewsRow> rows) {
        // 只调一次 classify：它做了遍历 + 分组，调两次除了浪费还会让两次结果可能不一致（缓存未命中时）
        Classifier.Result cls = Classifier.classify(rows);
        return withClassify(pack(cls.items()), cls);
    }

    /**
     * 输入 token 预估。
     *
     * <p>公式与文档 §7.6 一致：
     * {@code [Σ_L1(len(title)+min(len(content),1200)) + Σ_L2(len(title)+8)] / 1.7 + 400}。
     * 它同时用于"给用户看个量级"和"当硬闸"，所以必须和实际拼出来的字符数完全对应，
     * 不能各算一套。
     */
    public static int estimate(List<PackedNews> l1, List<PackedNews> l2) {
        long chars = 0;
        for (PackedNews p : l1) {
            chars += p.promptChars();
        }
        for (PackedNews p : l2) {
            chars += p.promptChars();
        }
        return (int) Math.ceil(chars / CHARS_PER_TOKEN) + PROMPT_OVERHEAD;
    }

    private static PackedNews withoutBody(PackedNews p) {
        return new PackedNews(p.id(), p.title(), p.publishTime(), null, p.kind(), p.score(), false);
    }

    private static void sortL2(List<PackedNews> l2) {
        l2.sort(Comparator.comparingLong(PackedNews::publishTime).reversed()
                .thenComparing(Comparator.comparingLong(PackedNews::id).reversed()));
    }

    /**
     * 取正文并截断。
     *
     * <p>截断处显式写上"…[正文已截断]"而不是静默切掉：模型看到这个标记才知道
     * "后面还有，但我没看到"，才不会拿半句话去推断结论。
     */
    private static String body(NewsRow row) {
        String c = row.content() == null ? "" : row.content();
        if (c.length() <= CONTENT_CAP) {
            return c;
        }
        return c.substring(0, CONTENT_CAP) + "…[正文已截断]";
    }

    /** 便于排查：某个类别进了多少条 L1。 */
    public static long countKind(List<PackedNews> list, NewsClass.Kind kind) {
        return list.stream().filter(p -> p.kind() == kind).count();
    }
}
