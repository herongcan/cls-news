package com.herc.clsnews.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 打包结果。
 *
 * <p>除了 L1/L2 两个清单，还带上了整条流水线的计数器 —— 分类剔了多少、合并了多少、
 * 因为预算被降级/丢弃了多少。这些东西不进 prompt，但必须能被看到：
 * 主线质量突然变差时，第一件事就是判断是"模型变了"还是"喂进去的东西变了"，
 * 而这些计数器是唯一能回答后者的东西。
 *
 * @param l1           拿正文的，按分数倒序
 * @param l2           只给标题的，按时间倒序
 * @param fetched      检索到多少条
 * @param promoDropped 剔除的导流条数
 * @param promoByTag   按栏目名的剔除计数
 * @param quoteMerged  被合并掉的条数
 * @param estTokens    预估输入 token
 * @param maxTokensIn  本次生效的硬闸
 * @param demoted      因超预算从 L1 降为 L2 的条数
 * @param dropped      因超预算彻底丢弃的条数（只在降级也不够时发生）
 */
public record PackResult(
        List<PackedNews> l1,
        List<PackedNews> l2,
        int fetched,
        int promoDropped,
        Map<String, Integer> promoByTag,
        int quoteMerged,
        int estTokens,
        int maxTokensIn,
        int demoted,
        int dropped) {

    /** L1 在前、L2 在后，拼 prompt 时按这个顺序写。 */
    public List<PackedNews> all() {
        List<PackedNews> out = new ArrayList<>(l1.size() + l2.size());
        out.addAll(l1);
        out.addAll(l2);
        return out;
    }

    public int candidates() {
        return l1.size() + l2.size();
    }

    public boolean trimmed() {
        return demoted > 0 || dropped > 0;
    }

    /**
     * 写给模型看的一句话。
     *
     * <p>为什么这句话不能省：把 80 条正文和 520 条标题混在一起而不声明，
     * 模型会默认"我看到的都是全文"，于是用只有标题的条目去推断细节，编出正文里根本没有的内容。
     * 明确告知分层之后，它对 L2 条目天然会更保守 —— 这比任何"请谨慎"的指令都有效。
     */
    public String digest() {
        return "共 " + fetched + " 条，其中 " + l1.size() + " 条含正文、" + l2.size() + " 条仅标题。";
    }

    /**
     * 给日志 / 排查用的一行摘要。
     *
     * <p>{@code promo} 带上栏目明细，是因为"今天少了一批新闻"这种问题，
     * 只能靠栏目名分布定位（是财联社换了栏目格式，还是判据误杀）。
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("fetched=").append(fetched)
                .append(" promo=").append(promoDropped)
                .append(" merged=").append(quoteMerged)
                .append(" L1=").append(l1.size())
                .append(" L2=").append(l2.size())
                .append(" est=").append(estTokens)
                .append(" cap=").append(maxTokensIn);
        if (demoted > 0) {
            sb.append(" demoted=").append(demoted);
        }
        if (dropped > 0) {
            sb.append(" dropped=").append(dropped);
        }
        if (!promoByTag.isEmpty()) {
            sb.append(" tags=").append(topTags(3));
        }
        return sb.toString();
    }

    private String topTags(int n) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        promoByTag.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted.toString();
    }
}
