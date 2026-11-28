package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;

import java.util.regex.Pattern;

/**
 * 确定性打分：决定一条候选拿正文（L1）还是只给标题（L2）。
 *
 * <p>为什么需要它：正文额度只有 80 条，而忙碌日有 241 条、满负荷能到 600 条。
 * 原设计里靠 {@code ORDER BY (level='A') DESC} 排序，但实测全库 level 只有 B(112) 和 C(1043)，
 * <b>A 级 0 条</b> —— 那行排序等于没写。所以必须换成内容信号。
 *
 * <p><b>只对标题打分，不看正文。</b> 这是有意的：
 * 电报正文平均 170 字，大量是对同一事件的背景复述；拿正文打分会让「正文里提了一句回购」
 * 的无关条目也拿到 +3，把真正的公司行为挤下去。而标题的写法高度规范（主体 + 动作 + 数字），
 * 信号密度反而更高。副作用是很便宜 —— 不为打分多读一个字段。
 *
 * <p>分数是累加的，一条可以同时命中多项（例如"某公司拟回购 2 亿元" → 金额 +3、公司行为 +3 = 6）。
 */
public final class Scorer {

    /** 金额 / 数量 / 百分比：最硬的信号，说明这条有具体事实。 */
    private static final Pattern AMOUNT = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:亿|万|千亿|亿美元|亿元|万元|%|个百分点|GW|MW|GWh|吨|台|辆|架|美元|元)");

    /** 政策与宏观：影响面广，但不如公司行为具体。 */
    private static final Pattern POLICY = Pattern.compile(
            "降准|降息|加息|关税|补贴|监管|央行|国常会|发改委|财政部|证监会|逆回购|MLF|LPR");

    /** 市场异动：本身就是新闻，但没有主体信息。 */
    private static final Pattern MOVES = Pattern.compile(
            "涨停|跌停|暴涨|暴跌|大涨|大跌|创新高|创新低|熔断|破发");

    /** 海外龙头 / 指数：国内读者关心的外部锚点。 */
    private static final Pattern GLOBAL_LEADER = Pattern.compile(
            "英伟达|特斯拉|苹果|微软|谷歌|亚马逊|台积电|三星|美联储|纳指|标普|道指|日经");

    /** 纯评论口吻：没有数字的"认为/表示"基本都是观点转述，价值低于事实。 */
    private static final Pattern OPINION = Pattern.compile("认为|表示|指出|分析师|专家|预计|有望");

    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    /** 进 L1 的分数线。3 = 至少命中一项硬信号（金额或公司行为）。 */
    public static final int L1_THRESHOLD = 3;

    private Scorer() {
    }

    /**
     * 打分。
     *
     * @param row 候选行
     * @return 分数，可能为负
     */
    public static int score(NewsRow row) {
        String t = row.title() == null ? "" : row.title();
        int s = 0;

        if (AMOUNT.matcher(t).find()) {
            s += 3;
        }
        // 词表复用 NewsClass 那一份：它同时是"这条不是行情播报"的排除条件，
        // 两处各写一份正则迟早会打架
        if (NewsClass.isCompanyAction(t)) {
            s += 3;
        }
        if (POLICY.matcher(t).find()) {
            s += 2;
        }
        if (MOVES.matcher(t).find()) {
            s += 2;
        }
        if (GLOBAL_LEADER.matcher(t).find()) {
            s += 1;
        }
        // 「无数字的评论」才扣分：带数字的观点（"预计增长 30%"）仍然是有信息量的
        if (OPINION.matcher(t).find() && !HAS_DIGIT.matcher(t).find()) {
            s -= 1;
        }
        // 海外非财经降权。这里直接调 isOverseas 而不看 kind：
        // 正文很短的海外条目会被归为 TERSE，若靠枚举判断就会漏掉这次降权。
        if (NewsClass.isOverseas(row.title(), row.content())) {
            s -= 2;
        }
        return s;
    }

    /**
     * 是否够格进 L1（给正文）。
     *
     * <p>QUOTE 和 TERSE 无论分数多少都进不了 L1 —— 它们的问题不是"不重要"，
     * 而是"正文里没有可省的额度"：行情播报的正文和标题说的是同一件事，
     * 极短正文本来就只有一句话。给它们正文纯属浪费预算。
     */
    public static boolean eligibleForBody(NewsClass.Kind kind, int score) {
        if (kind == NewsClass.Kind.QUOTE || kind == NewsClass.Kind.TERSE) {
            return false;
        }
        return score >= L1_THRESHOLD;
    }
}
