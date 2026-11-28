package com.herc.clsnews.agent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 把打包结果拼成真正送进模型的 {@code <news>} 段。
 *
 * <p><b>这个类的唯一职责是"让预估等于实际"。</b>
 * 在它出现之前，{@link PackedNews#promptChars()} 是一套算法、S1.2 拼字符串是另一套，
 * 两套各算一份的结果就是：预估永远差 40%，硬闸看起来在工作、实际什么都没拦住。
 * 现在两边的口径由 {@code PromptBuildTest} 钉死 —— 测试直接断言
 * {@code line(p).length() == p.promptChars()}，谁改了一边而没改另一边，构建立刻红。
 *
 * <h2>行格式</h2>
 * <pre>
 * [[1312]] 11-27 14:32 标题单行
 * 正文（仅 L1 有，已压平换行、已按上限截断）
 * </pre>
 *
 * <p>四个设计细节都有代价，不能随手改：
 * <ul>
 *   <li><b>id 用双方括号包起来</b>：模型最容易在引用上偷懒写成"据第 3 条"，
 *       给了一个显眼的、不像自然语言的记号 {{@code [[1312]]}}，它才会照抄。</li>
 *   <li><b>时间用 {@code MM-dd HH:mm} 而不是 {@code HH:mm}</b>：跨天窗口（英伟达模板是 7 天）
 *       只给时分会让模型把前天的消息当成今天的，"最近"这个词就失效了。</li>
 *   <li><b>正文压平换行</b>：财联社正文常有多段。保留换行会让"一条新闻"在文本里
 *       变成多行，模型数不清条数，也容易把下一段的开头当成新的一条。</li>
 *   <li><b>压平必须 1 字符换 1 字符</b>：{@code \n → 空格} 而不是删掉或正则折叠空白。
 *       一旦长度变了，{@code promptChars()} 就重新变成估算，前面三条白做。</li>
 * </ul>
 */
public final class PromptBuilder {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 行内时间戳。固定 11 字符，{@link Packer#PREFIX_CHARS} 的推导依赖这一点。 */
    static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZONE);

    private PromptBuilder() {
    }

    /**
     * 一条新闻在 prompt 里的完整文本，含结尾换行。
     *
     * @param p 打包后的一条
     * @return 与 {@link PackedNews#promptChars()} 长度严格相等的字符串
     */
    public static String line(PackedNews p) {
        StringBuilder sb = new StringBuilder(p.promptChars() + 8);
        sb.append(head(p)).append(flat(p.title())).append('\n');
        if (p.body() && p.content() != null) {
            sb.append(flat(p.content())).append('\n');
        }
        return sb.toString();
    }

    /**
     * 行前缀 {@code [[1312]] 11-27 14:32 }。
     *
     * <p>长度 = {@link Packer#PREFIX_CHARS} + id 的位数。这个拆分是 S1.2 复核出来的：
     * 原来那个 17 并不是算错，而是<b>只算了 id 之外的固定部分</b>
     * （{@code [[}2 + {@code ]]}2 + 空格1 + 时间11 + 空格1 = 17），
     * 漏掉的是 id 本身的位数 —— 600 条 × 4 位 ≈ 2400 字符 ≈ 1400 token，
     * 正好是"按 17 当总长"会漏掉的那部分。
     */
    public static String head(PackedNews p) {
        return "[[" + p.id() + "]] " + STAMP.format(Instant.ofEpochSecond(p.publishTime())) + " ";
    }

    /** 至少 1 位（id=0 时也占 1 个字符）。 */
    static int digits(long id) {
        return Long.toString(Math.max(id, 0L)).length();
    }

    /**
     * 压平换行。逐字符替换，长度不变 —— 这是能和 {@code promptChars()} 对账的前提。
     *
     * <p>顺带把 {@code \r\n} 变成两个空格，读起来多一个空格，但比"长度对不上导致硬闸失效"划算得多。
     */
    static String flat(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    /** L1 在前、L2 在后，与 {@link PackResult#all()} 同序。返回值不含 {@code <news>} 标签，标签由模板写。 */
    public static String newsBlock(PackResult r) {
        return blockOf(r.all());
    }

    /** 同上，但接受任意条目列表 —— delta 只拼新增的那部分。 */
    static String blockOf(java.util.List<PackedNews> items) {
        StringBuilder sb = new StringBuilder(items.size() * 80);
        for (PackedNews p : items) {
            sb.append(line(p));
        }
        return sb.toString();
    }

    /** 仅标题的一行，用于 {@code <previous_titles>}：格式与正文行一致，模型才不会把两种格式当成两类东西。 */
    static String titleLine(PackedNews p) {
        return head(p) + flat(p.title()) + '\n';
    }

    /** 给用户看的时间范围，精确到分钟 —— 只写日期会让"今天上午"这种说法失去依据。 */
    private static final DateTimeFormatter RANGE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);

    /**
     * 渲染完整的 user 提示词。
     *
     * <p><b>{@code {{news}}} 必须最后替换</b>：新闻正文里出现 {@code {{count}}} 这种字符串
     * 概率极低但不是零，先替换正文会让后面的占位符替换命中正文内部。
     * {@link String#replace} 是字面替换（不吃 {@code $} 和反斜杠），所以不需要转义。
     *
     * @param t         模板
     * @param r         打包结果
     * @param fromEpoch 窗口起点，epoch 秒
     * @param toEpoch   窗口终点，epoch 秒
     */
    public static String render(AgentTemplate t, PackResult r, long fromEpoch, long toEpoch) {
        return t.user()
                .replace("{{from}}", RANGE_STAMP.format(Instant.ofEpochSecond(fromEpoch)))
                .replace("{{to}}", RANGE_STAMP.format(Instant.ofEpochSecond(toEpoch)))
                .replace("{{count}}", String.valueOf(r.candidates()))
                .replace("{{digest}}", r.digest())
                // 全量没有"上一版"，占位符换成空串而不是留着 —— 留着的话模型会看到
                // 一个没被替换的 {{previous}}，从而开始"猜测"它的含义
                .replace("{{previous}}", "")
                // 全量没有"增量"这回事，{{mode}} 换成空串；delta 时这里会是下面那段硬指令
                .replace("{{mode}}", "")
                .replace("{{news}}", newsBlock(r));
    }

    // ---------------------------------------------------------------- 增量

    /**
     * {@code <previous_evidence>} 最多带几条原文。
     *
     * <p>这是成本闸，不是美观问题：5 条主线 × 平均 5 个引用就能把整份正文又塞回来，
     * 增量就白做了（省下的正文预算全被证据段吃掉）。超出的回退成只给标题。
     */
    public static final int MAX_EVIDENCE = 20;

    /**
     * 增量场景插在 {@code <news>} 之后、任务要求之前的那段硬指令。
     *
     * <p><b>这段话是 2026-11-28 真模型验收逼出来的。</b>没有它的时候，模型看到
     * {@code <news>} 里只有 3 条新增，就照着这 3 条写了 3 条主线 —— 旧的 5 条
     * （美股大涨、黄金、霍尔木兹、美联储、并购）全部消失。输入结构全对，引用也全真，
     * 输出却是"今日新增：1. 2. 3."，也就是文档 §10.4 明令禁止的那种形态。
     *
     * <p>根因不是模型笨，是<b>任务描述与输入结构不一致</b>：模板只说"提炼今日主线"，
     * 而 {@code <news>} 的语义已经变成了"只有增量"。不显式说明，模型会按字面理解 ——
     * "今天就这些新闻"。
     *
     * <p>位置也有讲究：放在 {@code <news>} 之后、任务要求之前。放最前面会被当成背景信息，
     * 放最后又和输出格式说明打架；放在"开始干活"的那一句前面，权重刚好。
     */
    static final String DELTA_MODE_HINT = """

            注意：上面 <news> 里只是本次新增的条目。你要输出的是今天整体的主线，不是增量列表。
            请结合 <previous> 的旧结论和 <previous_titles> 的全部标题重新排一遍：
            仍然成立的保留，有新消息加强的合并，已经不成立的删掉。
            不要只写新增的这几条。""";

    /**
     * 增量 prompt 的产物。
     *
     * @param user          完整 user 提示词
     * @param chars         字符数，调用方据此二次核对硬闸
     * @param evidenceCount 实际给了正文的证据条数
     * @param trimmed       true = 因超预算砍过证据，日志里要能看出来
     */
    public record DeltaPrompt(String user, int chars, int evidenceCount, boolean trimmed) {
    }

    /**
     * 拼增量提示词：四段（旧结论 / 旧标题全量 / 旧引用原文 / 本次新增）。
     *
     * <p><b>为什么不能"在旧答案上打补丁"</b>：主线是全局重排的产物。一条"白银大涨 7%"
     * 的新消息进来，可能让贵金属从第 4 升到第 1，也可能让原来两条独立主线合并成一条。
     * 只喂增量，模型看不到旧的完整排序依据，只能局部修补，最后输出「今日新增：1. 2. 3.」——
     * 那是增量播报，不是今日主线。所以四段里前三段的作用是<b>让模型重新排一次</b>，
     * 而不是让它"接着写"。
     *
     * <p>三段各有用处，缺一不可：{@code previous} 给结论，{@code previous_titles} 给全貌
     * （否则模型不知道当时还有哪些没进主线的条目），{@code previous_evidence} 给证据
     * （否则它判断"这条旧主线还成不成立"只能靠猜，容易照抄）。
     *
     * <p><b>切分靠 base 的水位线，不靠额外存储</b>：{@code id <= baseWatermark} 的就是
     * 上次能看到的全部条目（同一天同一窗口，分类又是确定性的）。这样不必把候选集快照下来，
     * 少一张表也就少一处会和实际不一致的地方。
     *
     * @param baseWatermark 上一版 full 的水位线
     * @param previous      上一版结论
     * @param baseCreatedAt 上一版生成时间，epoch 秒
     * @param baseCandidates 上一版当时的候选条数
     * @param maxTokensIn   硬闸。超了就先砍证据条数：20 → 10 → 5 → 0
     */
    public static DeltaPrompt renderDelta(AgentTemplate t, PackResult r, long baseWatermark,
                                          Answer previous, long baseCreatedAt, int baseCandidates,
                                          long fromEpoch, long toEpoch, int maxTokensIn) {
        java.util.List<PackedNews> old = new java.util.ArrayList<>();
        java.util.List<PackedNews> added = new java.util.ArrayList<>();
        for (PackedNews p : r.all()) {
            if (p.id() <= baseWatermark) {
                old.add(p);
            } else {
                added.add(p);
            }
        }

        java.util.Set<Long> refIds = new java.util.LinkedHashSet<>(previous.allRefs());
        java.util.List<PackedNews> evidence = new java.util.ArrayList<>();
        for (PackedNews p : r.all()) {
            if (refIds.contains(p.id())) {
                evidence.add(p);
            }
        }

        int[] caps = {MAX_EVIDENCE, 10, 5, 0};
        DeltaPrompt last = null;
        for (int cap : caps) {
            String prev = previousBlock(previous, baseCreatedAt, baseCandidates)
                    + titlesBlock(old, baseCandidates)
                    + evidenceBlock(evidence, cap);
            String user = fillDelta(t, r, old, added, fromEpoch, toEpoch, prev);
            int kept = Math.min(cap, evidence.size());
            last = new DeltaPrompt(user, user.length(), kept, kept < evidence.size());
            if (tokens(user.length()) <= maxTokensIn || cap == 0) {
                return last;
            }
        }
        return last;
    }

    private static String fillDelta(AgentTemplate t, PackResult r, java.util.List<PackedNews> old,
                                    java.util.List<PackedNews> added, long fromEpoch, long toEpoch,
                                    String previous) {
        // 两个占位符都是硬性的：少了 {{previous}}，模型只看到 12 条新新闻，会写成增量播报；
        // 少了 {{mode}}，输入结构对了但任务描述还停留在全量，实测同样会写成"今日新增 1. 2. 3."
        for (String ph : new String[]{"{{previous}}", "{{mode}}"}) {
            if (!t.user().contains(ph)) {
                throw new IllegalStateException(
                        "模板 " + t.id() + " 的 user 段缺少 " + ph + " 占位符，无法拼增量提示词");
            }
        }
        StringBuilder digest = new StringBuilder();
        digest.append("今日共 ").append(old.size() + added.size()).append(" 条")
                .append("，本次新增 ").append(added.size()).append(" 条")
                .append("（").append(countBody(added)).append(" 条含正文、")
                .append(added.size() - countBody(added)).append(" 条仅标题）。");

        return t.user()
                .replace("{{from}}", RANGE_STAMP.format(Instant.ofEpochSecond(fromEpoch)))
                .replace("{{to}}", RANGE_STAMP.format(Instant.ofEpochSecond(toEpoch)))
                .replace("{{count}}", String.valueOf(old.size() + added.size()))
                .replace("{{digest}}", digest.toString())
                // 和全量一样：{{news}} 最后替换，避免正文里的字面量被二次替换
                .replace("{{previous}}", previous)
                .replace("{{mode}}", DELTA_MODE_HINT)
                .replace("{{news}}", blockOf(added));
    }

    private static int countBody(java.util.List<PackedNews> items) {
        int n = 0;
        for (PackedNews p : items) {
            if (p.body() && p.content() != null && !p.content().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static String previousBlock(Answer previous, long baseCreatedAt, int baseCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("<previous>\n")
                .append("上一版结论（").append(STAMP.format(Instant.ofEpochSecond(baseCreatedAt)))
                .append(" 生成 · 当时 ").append(baseCandidates).append(" 条 · 共 ")
                .append(previous.bullets().size()).append(" 条主线）\n");
        int i = 1;
        for (Answer.Bullet b : previous.bullets()) {
            sb.append(i++).append(". ").append(b.text());
            for (Long id : b.refs()) {
                sb.append("  [[").append(id).append("]]");
            }
            sb.append('\n');
        }
        sb.append("</previous>\n");
        return sb.toString();
    }

    private static String titlesBlock(java.util.List<PackedNews> old, int baseCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("<previous_titles>\n")
                .append("上一版包含的全部 ").append(old.size())
                .append(" 条标题（仅标题，无正文；条数按当时 ").append(baseCandidates).append(" 条计）\n");
        for (PackedNews p : old) {
            sb.append(titleLine(p));
        }
        sb.append("</previous_titles>\n");
        return sb.toString();
    }

    private static String evidenceBlock(java.util.List<PackedNews> evidence, int cap) {
        int n = Math.min(cap, evidence.size());
        StringBuilder sb = new StringBuilder();
        sb.append("<previous_evidence>\n");
        if (n == 0) {
            sb.append("（本次不提供旧引用原文，判断旧结论是否成立时只能依据标题）\n");
        } else {
            sb.append("上面结论引用到的 ").append(n).append(" 条原文（用来核对旧结论是否还成立）\n");
            for (int i = 0; i < n; i++) {
                sb.append(line(evidence.get(i)));
            }
        }
        sb.append("</previous_evidence>\n");
        return sb.toString();
    }

    /**
     * 字符数 → token 估算。
     *
     * <p>只做一件事：让"给用户看的报价"和"硬闸"共用同一个折算系数。
     * 不引 tokenizer 的理由见 {@link Packer#CHARS_PER_TOKEN}。
     */
    public static int tokens(long chars) {
        return (int) Math.ceil(chars / Packer.CHARS_PER_TOKEN);
    }
}
