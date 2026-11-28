package com.herc.clsnews.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把模型输出解析成 {@link Answer}，并做引用校验。
 *
 * <p><b>为什么校验不能省</b>：模型编 id 是这个任务里最高频的错误形态。
 * 实测同类任务里它会写出"[[1312]][[1301]]"这种看起来完全合理的引用，
 * 但 1312 可能是别的日期的新闻、或者根本不存在。界面上引用是可点击的，
 * 点过去发现原文跟结论无关，比不显示引用更伤可信度。
 *
 * <p>校验是<b>减法</b>而不是拒绝：越界的 ref 剥掉，剥到一条不剩才丢整个 bullet，
 * 全丢才判 rejected。理由是"大部分引用正确"仍然是可用的结果，
 * 一刀切拒绝会让模型偶尔多写一个 id 就白花一万 token。
 *
 * <p>解析容忍三类实际的脏输出（都不是假设，是这类模型的常态）：
 * <ol>
 *   <li>{@code ```json} 围栏</li>
 *   <li>JSON 前后夹一句"好的，以下是今日主线"</li>
 *   <li>refs 里混字符串 {@code "1312"} 和数字 {@code 1312}</li>
 * </ol>
 */
public final class AnswerParser {

    private final ObjectMapper mapper;

    public AnswerParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param raw           模型原始输出
     * @param allowed       本次候选集的 id 集合，白名单
     * @param fallbackTitle 模型没给 title 时用它
     * @return 解析结果。{@link Result#empty()} 为 true 表示整次应当判 rejected
     * @throws IllegalStateException 完全不是 JSON，或没有 bullets 字段
     */
    public Result parse(String raw, Set<Long> allowed, String fallbackTitle) {
        JsonNode root = readJson(raw);

        String title = text(root, "title");
        if (title.isBlank()) {
            title = fallbackTitle;
        }

        JsonNode arr = root.path("bullets");
        if (!arr.isArray()) {
            throw new IllegalStateException("模型输出没有 bullets 数组：" + clip(raw, 160));
        }

        List<Answer.Bullet> kept = new ArrayList<>();
        int droppedBullets = 0;
        int droppedRefs = 0;
        int badRefsInKept = 0;

        for (JsonNode b : arr) {
            String txt = text(b, "text");
            if (txt.isBlank()) {
                droppedBullets++;
                continue;
            }
            List<Long> refs = new ArrayList<>();
            int bad = 0;
            for (JsonNode r : b.path("refs")) {
                long id = asId(r);
                if (id <= 0) {
                    bad++;
                    continue;
                }
                if (!allowed.contains(id)) {
                    bad++;
                    continue;
                }
                if (!refs.contains(id)) {
                    refs.add(id);
                }
            }
            droppedRefs += bad;
            if (refs.isEmpty()) {
                // 一条没有引用的结论无法验证，宁可不要
                droppedBullets++;
                continue;
            }
            badRefsInKept += bad;
            kept.add(new Answer.Bullet(txt, refs));
        }

        boolean insufficient = root.path("insufficient").asBoolean(false);
        if (kept.isEmpty()) {
            insufficient = true;
        }
        String note = "kept=" + kept.size() + " droppedBullets=" + droppedBullets
                + " droppedRefs=" + droppedRefs + " badRefsInKept=" + badRefsInKept;
        return new Result(new Answer(title, kept, insufficient), droppedBullets, droppedRefs, note);
    }

    // ---------------------------------------------------------------- 内部

    private JsonNode readJson(String raw) {
        String s = stripFence(raw);
        try {
            return mapper.readTree(s);
        } catch (Exception first) {
            // 前后夹了解释文字：截取第一个 { 到最后一个 } 再试一次
            int from = s.indexOf('{');
            int to = s.lastIndexOf('}');
            if (from >= 0 && to > from) {
                try {
                    return mapper.readTree(s.substring(from, to + 1));
                } catch (Exception ignored) {
                    // 落到下面统一抛
                }
            }
            throw new IllegalStateException("模型输出不是合法 JSON：" + clip(raw, 160), first);
        }
    }

    /** 去掉 {@code ```json} / {@code ```} 围栏。没有围栏时原样返回。 */
    static String stripFence(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstLine = t.indexOf('\n');
        if (firstLine > 0) {
            t = t.substring(firstLine + 1);
        }
        int end = t.lastIndexOf("```");
        if (end >= 0) {
            t = t.substring(0, end);
        }
        return t.trim();
    }

    /** 兼容数字与字符串两种形态；认不出返回 -1。 */
    private static long asId(JsonNode n) {
        if (n == null || n.isNull()) {
            return -1;
        }
        if (n.isNumber()) {
            return n.asLong(-1);
        }
        String s = n.asText("").trim();
        if (s.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("").trim();
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /**
     * @param answer         校验后的结果
     * @param droppedBullets 因为没有正文或没有任何合法引用而被丢掉的结论数
     * @param droppedRefs    被剥掉的不存在引用数
     * @param note           一行摘要，写进 agent_step 便于事后判断"是模型变差了还是数据变了"
     */
    public record Result(Answer answer, int droppedBullets, int droppedRefs, String note) {
        public boolean empty() {
            return answer == null || answer.empty();
        }

        /** 有结论、但引用被剥过的，值得在日志里提一句。 */
        public boolean partiallyDegraded() {
            return !empty() && droppedRefs > 0;
        }

        public Set<Long> refs() {
            return new LinkedHashSet<>(answer.allRefs());
        }
    }
}
