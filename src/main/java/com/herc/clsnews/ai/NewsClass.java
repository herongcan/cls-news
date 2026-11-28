package com.herc.clsnews.ai;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 新闻条目的纯规则分类。
 *
 * <p>为什么要单独建一个类、而不是在调用处直接写 {@code title.startsWith("【")}：
 * 同一个判据至少要用在三处 —— 分析接口的前置拦截、Agent 候选集过滤、将来的列表展示策略。
 * 分散写必然漂移，而这类"看起来一样的判断"一旦不一致，表现出来是
 * 「Agent 里看不到这条，但卡片上的分析按钮还能点」，排查成本极高。
 *
 * <p>全部是确定性静态方法：无状态、无外部依赖、可单测、不花一分钱。
 * 这一点很关键 —— 检索阶段的每一步（分类、合并、打分、打包）都必须能在没有网络、
 * 没有 key、不产生任何费用的前提下被断言，否则调一次 Agent 才能验一次，成本高到没人愿意验。
 */
public final class NewsClass {

    private NewsClass() {
    }

    /**
     * 条目的路由类别。只决定"这条怎么进 prompt"，不决定"这条值多少钱"（那是 {@code Scorer} 的事）。
     */
    public enum Kind {
        /** 栏目导流，标题以「【」开头。整体剔除，不进候选集。 */
        PROMO,
        /** 行情播报（指数/期货/现货/ETF + 涨跌 + 数字）。同类合并，且强制只给标题。 */
        QUOTE,
        /** 正文极短。信息都在标题里，强制只给标题。 */
        TERSE,
        /** 海外非财经。降权，但**不删** —— 偶尔有"某国大选影响出口"这种真信息。 */
        OVERSEAS,
        /** 其余，正常打分。 */
        NORMAL
    }

    /** 行情标的词。命中它才可能被归为 QUOTE。 */
    private static final Set<String> QUOTE_SUBJECT = Set.of(
            "指数", "期货", "现货", "ETF", "汇率", "国债", "原油", "黄金", "白银", "铜", "钯金", "天然气");

    /** 涨跌词。与标的词、数字三者同时命中才判 QUOTE。 */
    private static final Pattern QUOTE_MOVE = Pattern.compile("涨|跌|升|破|回落|走低|走高|收窄|扩大");

    /**
     * 公司行为 / 监管处置动词。出现这些说明有明确主体在做具体的事，"行情播报"的判断必须让位。
     *
     * <p>监管处置词（立案/处罚/退市/摘牌/警示）是 2026-11-27 体检时补进来的：
     * 原表只有"回购/中标/收购"这类主动行为，结果「*ST卓然：收到股票终止上市决定」
     * 被打 0 分、只能进 L2 —— 而摘牌是当天最该上主线的一条。
     * 全库此类标题 10 条，量不大，但漏掉的正好是最不能漏的那类。
     */
    private static final Pattern COMPANY_ACTION = Pattern.compile(
            "回购|增持|减持|中标|签约|收购|并购|停牌|复牌|业绩预告|定增|配股|分红|重组|易主|借壳|上市|拟"
                    + "|立案|处罚|问询|警示|退市|摘牌|终止上市|冻结|违约");

    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    /** 海外非财经词。只在"同时没有任何财经词"时才成立。 */
    private static final Set<String> OVERSEAS_WORDS = Set.of(
            "军舰", "总统", "大选", "袭击", "导弹", "航母", "议会", "首相", "王室", "空袭");

    /** 财经词。用来给 OVERSEAS 判据兜底：命中任一就不算"海外非财经"。 */
    private static final Set<String> FINANCE_WORDS = Set.of(
            "股", "债", "汇", "关税", "贸易", "石油", "原油", "黄金", "铜", "芯片", "半导体",
            "出口", "进口", "通胀", "利率", "央行", "经济", "涨", "跌", "公司", "市场");

    /** 正文短于这个长度就只给标题：这个长度以下基本只剩一句结论，没有可压缩的空间。 */
    public static final int TERSE_CHARS = 40;

    /**
     * 是否栏目导流条目。
     *
     * <p>判据是<b>结构特征</b>（标题以「【」开头），不是栏目白名单。理由来自实测：
     * 全库 1157 条里带「【」的 37 条<b>全部</b>位于标题首位，无一例外；
     * 而白名单需要随财联社上新栏目持续维护，漏一个就是漏一整批。
     *
     * <p>代价已确认并被接受：这 37 条里有 13 条其实有内容
     * （【电报解读】3、【机构调研】3、【研选·数据】4、【财联社早知道】1、
     * 【九点特供】1、【玩转ETF】1），会被一并排除，平均每天少 1–2 条。
     * 补偿办法是过滤时按栏目名计次打日志 —— 出现高频新栏目或单日误杀异常时能第一时间发现，
     * 而不是安静地少掉一批新闻。
     *
     * @param title 标题，可为 null
     * @return true 表示这条是栏目导流，不含可分析的有效信息
     */
    public static boolean isPromo(String title) {
        return title != null && title.startsWith("【");
    }

    /**
     * 路由分类。
     *
     * <p><b>判定顺序是有意的</b>，不能随便调换：
     * <ol>
     *   <li>{@code PROMO} 最先 —— 导流条的正文是荐股话术，后面所有判据对它都没有意义。</li>
     *   <li>{@code QUOTE} 在 {@code TERSE} 之前 —— 行情播报的正文常常也很短（"截至10:30，报..."），
     *       若先判 TERSE，这些条目就失去了被合并的机会，同一天会留下十几条重复播报。</li>
     *   <li>{@code OVERSEAS} 放在 {@code TERSE} 之后，是本次实现的一个已知取舍（见下）。</li>
     * </ol>
     *
     * <p><b>已知取舍</b>：正文很短、同时又是海外非财经的条目会归为 {@code TERSE}，
     * 枚举上看不出它该降权。所以降权<b>不依赖这个枚举</b> —— {@code Scorer} 会无条件再调一次
     * {@link #isOverseas}。这里少一个枚举分支不影响结果，只是分类统计上会少计几条。
     * 之所以不把 OVERSEAS 提到 TERSE 前面：这个枚举的主要用途是路由（剔除/合并/只给标题），
     * 而"降权"是打分维度，本来就该独立于路由。
     *
     * @param title   标题
     * @param content 正文，可为 null
     * @return 路由类别，永不为 null
     */
    public static Kind kind(String title, String content) {
        if (isPromo(title)) {
            return Kind.PROMO;
        }
        String t = title == null ? "" : title;
        String c = content == null ? "" : content;
        if (isQuote(t)) {
            return Kind.QUOTE;
        }
        if (c.trim().length() < TERSE_CHARS) {
            return Kind.TERSE;
        }
        if (isOverseas(t, c)) {
            return Kind.OVERSEAS;
        }
        return Kind.NORMAL;
    }

    /**
     * 是否含公司行为 / 监管处置动词。
     *
     * <p>暴露成 public 是为了让 {@code Scorer} 复用同一份词表 —— 它同时是
     * "这条不是行情播报"的排除条件，和"这条值 3 分"的正向信号。
     * 两处各写一份正则，迟早会出现"分类认为它是行情播报、打分认为它有公司行为"的矛盾。
     */
    public static boolean isCompanyAction(String title) {
        return title != null && COMPANY_ACTION.matcher(title).find();
    }

    /**
     * 是否行情播报：标的词 + 涨跌词 + 数字，且没有任何公司行为动词。
     *
     * <p>"无公司主体"这一条是拿公司行为动词近似的，不是真的做实体识别。
     * 理由：财联社的行情播报句式高度固定（"恒生科技指数跌幅扩大至3%"），
     * 而真正的公司新闻几乎一定带"回购/中标/拟/收购"这类动词。
     * 用动词判比用公司库判稳 —— 公司库要维护，动词表不会因为新上市一家公司就失效。
     */
    public static boolean isQuote(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        if (isCompanyAction(title)) {
            return false;
        }
        boolean hasSubject = QUOTE_SUBJECT.stream().anyMatch(title::contains);
        return hasSubject && QUOTE_MOVE.matcher(title).find() && HAS_DIGIT.matcher(title).find();
    }

    /**
     * 是否海外非财经：命中海外词，且一个财经词都没有。
     *
     * <p>单独暴露成 public 是因为它同时是打分信号：{@code Scorer} 会无视路由类别直接调它，
     * 这样"正文太短被归为 TERSE"的海外条目也不会漏掉降权。
     */
    public static boolean isOverseas(String title, String content) {
        String t = (title == null ? "" : title) + " " + (content == null ? "" : content);
        if (OVERSEAS_WORDS.stream().noneMatch(t::contains)) {
            return false;
        }
        return FINANCE_WORDS.stream().noneMatch(t::contains);
    }

    /**
     * 取栏目名用于统计，形如 {@code 【盘中宝】}；解析不出时返回 {@code 【?】}。
     *
     * <p>只为了在日志里回答一个问题："今天被剔除的这批，都是哪些栏目"。
     * 没有这个统计，误杀（比如某天财联社改了栏目格式，一次少掉 20 条）会完全无声。
     */
    public static String promoTag(String title) {
        if (title == null) {
            return "【?】";
        }
        int end = title.indexOf('】');
        return end > 1 ? title.substring(0, end + 1) : "【?】";
    }
}
