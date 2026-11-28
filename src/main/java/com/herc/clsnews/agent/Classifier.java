package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分类与合并：把一天的原始电报压成"值得进 prompt 的候选集"。
 *
 * <p>全库 1157 条实测下来，真正无效的只有 3.2%（栏目导流 37 条）。所以这一步的价值
 * <b>不是"排噪"，而是别把正文预算花在低密度条目上</b> —— 一条 30 字的
 * 「恒生科技指数跌幅扩大至3%」如果占了 1200 字的正文额度，就等于挤掉了三条真新闻。
 *
 * <p>三档动作，不是一刀切过滤：
 * <table>
 *   <tr><th>类别</th><th>动作</th><th>理由</th></tr>
 *   <tr><td>PROMO</td><td>剔除</td><td>正文是荐股话术，判它必然得出"利好某某板块"，等于花钱买一句广告</td></tr>
 *   <tr><td>QUOTE</td><td>合并</td><td>不是噪声，是低密度；合并后还能多出"持续扩大"这个趋势信号</td></tr>
 *   <tr><td>TERSE / OVERSEAS</td><td>降级 / 降权</td><td>信息在标题里，或主题不相关但不该删</td></tr>
 * </table>
 *
 * <p>全部是确定性规则，可单测、不花钱。这一点决定了整个流水线能不能在 CI 里跑 ——
 * 如果这一步需要调模型，那它就只能靠"人肉点一次看看"，规则永远调不准。
 */
public final class Classifier {

    /**
     * 涨跌标记词，按长度从长到短排列。
     *
     * <p>顺序不能乱：正则是最左优先匹配，{@code 涨幅} 必须排在 {@code 涨} 前面，
     * 否则「恒生科技指数涨幅扩大」会在「涨」处就切断，标的名变成「恒生科技指数」刚好还对，
     * 但「现货白银上涨3%」会切在「上」和「涨」之间，标的名变成「现货白银上」—— 合并键就散了。
     */
    private static final Pattern MOVE_MARK = Pattern.compile(
            "涨幅|跌幅|上涨|下跌|大涨|大跌|暴涨|暴跌|走高|走低|回落|反弹|升破|跌破|突破|创新高|创新低|现报|报|涨|跌");

    /** 幅度：第一个百分数。没写百分数的（"报 1234 点"）按 0 处理，靠时间做次级排序。 */
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");

    private Classifier() {
    }

    /**
     * 分类结果。
     *
     * @param items        过滤、合并之后的候选，保持原始时间倒序
     * @param fetched      进来多少条
     * @param promoDropped 剔除的导流条数
     * @param promoByTag   按栏目名的剔除计数，用来发现"某天突然少了一批"
     * @param quoteMerged  被合并掉的条数（组大小减一求和），不是组数
     */
    public record Result(
            List<Classified> items,
            int fetched,
            int promoDropped,
            Map<String, Integer> promoByTag,
            int quoteMerged) {

        public int kept() {
            return items.size();
        }
    }

    /**
     * 分类 + 合并。
     *
     * @param rows 检索结果，建议已按发布时间倒序（合并时的平手判据依赖这个顺序，但不强依赖）
     * @return 分类结果
     */
    public static Result classify(List<NewsRow> rows) {
        List<NewsRow> input = rows == null ? List.of() : rows;
        Map<String, Integer> promoByTag = new LinkedHashMap<>();
        List<Classified> kept = new ArrayList<>(input.size());
        int promoDropped = 0;

        for (NewsRow row : input) {
            NewsClass.Kind kind = NewsClass.kind(row.title(), row.content());
            if (kind == NewsClass.Kind.PROMO) {
                promoDropped++;
                promoByTag.merge(NewsClass.promoTag(row.title()), 1, Integer::sum);
                continue;
            }
            kept.add(Classified.of(row, kind));
        }

        // 合并掉的条数 = 走进来的候选数 - 走出去的候选数。不单独计数，
        // 是为了让这个数天然和 items 对得上 —— 两个独立计数迟早会不一致。
        List<Classified> merged = mergeQuotes(kept);
        int quoteMerged = kept.size() - merged.size();
        return new Result(merged, input.size(), promoDropped, promoByTag, quoteMerged);
    }

    /**
     * 合并同一标的、同一方向的连续播报。
     *
     * <p>实测 105 条行情播报里有 23 组重复、涉及 66 条（韩国 KOSPI 一天播 9 次、钯金期货 5 次），
     * 合并后剩约 62 条。
     *
     * <p><b>为什么按"标的 + 方向"而不是按标题相似度</b>：标题是「跌幅扩大至2% / 3% / 4%」，
     * 字符相似度其实很高，但一旦用相似度就得定阈值，而阈值没有客观依据；
     * 而"标的 + 方向"是结构化的 —— 恒生科技指数涨和恒生科技指数跌本来就是两件事，
     * 不该合并成一条（涨跌来回震荡恰恰是重要信号，合并掉就丢了这个信息）。
     */
    private static List<Classified> mergeQuotes(List<Classified> kept) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < kept.size(); i++) {
            Classified c = kept.get(i);
            if (c.kind() == NewsClass.Kind.QUOTE) {
                groups.computeIfAbsent(mergeKey(c.row().title()), k -> new ArrayList<>()).add(i);
            }
        }
        if (groups.isEmpty()) {
            return kept;
        }

        List<Classified> out = new ArrayList<>(kept.size());
        // 已处理过的下标：被合并掉的整组都跳过，只保留代表
        boolean[] drop = new boolean[kept.size()];
        for (List<Integer> idx : groups.values()) {
            if (idx.size() < 2) {
                continue;
            }
            int winner = pickWinner(kept, idx);
            for (int i : idx) {
                if (i != winner) {
                    drop[i] = true;
                }
            }
        }
        for (int i = 0; i < kept.size(); i++) {
            if (drop[i]) {
                continue;
            }
            Classified c = kept.get(i);
            if (c.kind() == NewsClass.Kind.QUOTE) {
                int n = groups.getOrDefault(mergeKey(c.row().title()), List.of()).size();
                out.add(n > 1 ? new Classified(c.row(), c.kind(), n) : c);
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 组内代表：先看幅度最大，平手取最新一条。
     *
     * <p>取幅度最大而不是最新：「跌幅扩大至4%」比「跌2%」信息量大，而两条的时间戳只差几十分钟，
     * 取最新会得到一条更弱的播报。时间只作为平手判据 —— 同样 4% 被播了两次时，取时间新的那条。
     */
    private static int pickWinner(List<Classified> kept, List<Integer> idx) {
        int best = idx.get(0);
        for (int i : idx) {
            Classified a = kept.get(i);
            Classified b = kept.get(best);
            double ma = magnitude(a.row().title());
            double mb = magnitude(b.row().title());
            if (ma > mb + 1e-9) {
                best = i;
            } else if (Math.abs(ma - mb) <= 1e-9) {
                long ta = a.row().publishTime();
                long tb = b.row().publishTime();
                if (ta > tb || (ta == tb && a.row().id() > b.row().id())) {
                    best = i;
                }
            }
        }
        return best;
    }

    /** 合并键 = 标的 + 方向。平手判据的稳定性全靠它，所以单独可见，测试能直接断言。 */
    static String mergeKey(String title) {
        return subject(title) + "|" + direction(title);
    }

    /**
     * 从标题里切出标的：第一个涨跌标记之前的部分。
     *
     * <p>切不出（标题以涨跌词开头）时退回前 10 个字 —— 这种情况极少，
     * 退回一个稳定的截断总比抛异常好：合并是优化，不能因为一条奇怪的标题让整次 run 失败。
     */
    static String subject(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            return "";
        }
        Matcher m = MOVE_MARK.matcher(t);
        if (m.find()) {
            String s = t.substring(0, m.start()).trim();
            s = stripTrailing(s);
            if (s.length() >= 2) {
                return s;
            }
        }
        return t.substring(0, Math.min(10, t.length()));
    }

    /** 去掉切分后残留的连词与标点，否则「恒生科技指数，」和「恒生科技指数」会被当成两个标的。 */
    private static String stripTrailing(String s) {
        String r = s;
        while (!r.isEmpty()) {
            char c = r.charAt(r.length() - 1);
            if (c == '，' || c == ',' || c == '之' || c == '的' || c == '：' || c == ':' || Character.isWhitespace(c)) {
                r = r.substring(0, r.length() - 1);
            } else {
                break;
            }
        }
        return r.trim();
    }

    /**
     * 方向：涨 / 跌 / 平。
     *
     * <p>先判跌、再判涨，且要求互斥 —— 「跌幅收窄但仍下跌」这类标题会同时命中两边，
     * 互斥时归为 flat，宁可让它单独成组（多花几十个字符），也不要把涨和跌合到一起。
     */
    static String direction(String title) {
        String t = title == null ? "" : title;
        boolean down = containsAny(t, "跌", "走低", "回落", "下滑", "下降");
        boolean up = containsAny(t, "涨", "走高", "反弹", "上升", "升破", "突破");
        if (down && !up) {
            return "down";
        }
        if (up && !down) {
            return "up";
        }
        return "flat";
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 标题里的第一个百分数；没有则 0。 */
    static double magnitude(String title) {
        if (title == null) {
            return 0;
        }
        Matcher m = PERCENT.matcher(title);
        if (!m.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
