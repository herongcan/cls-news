package com.herc.clsnews.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code prompts/agent/templates.md} 的解析结果。
 *
 * <p><b>为什么模板要放文件不放代码</b>：调提示词是这个项目里最高频的操作。
 * 写在 Java 字符串里，改一句"每条结论不超过 60 字"都要重新编译、重跑测试、重新部署；
 * 放资源文件里改完重启即可。代价是格式错了要等到运行时才发现 —— 所以 {@link #parse}
 * 是纯函数、可单测，且所有校验都在解析期完成，不允许"跑起来才炸"。
 *
 * <h2>文件格式</h2>
 * <pre>
 * ## hot-today          ← 模板 id，不能叫 system / user
 * title: 今日财经新闻热点汇集
 * window: 1d
 * min-hits: 5
 * ## system            ← 该模板的 system 段
 * …
 * ## user              ← 该模板的 user 段
 * …
 * </pre>
 *
 * <p>解析是<b>有状态</b>的：{@code ## system} / {@code ## user} 归属于它之前最近的那个模板 id。
 * 不能用正则各段独立匹配，否则三个模板会共用同一段 system。
 */
public final class AgentTemplates {

    /** 模板 id 撞名这两个词会直接破坏分段结构，比"悄悄解析错"好。 */
    private static final List<String> RESERVED = List.of("system", "user");

    private final Map<String, AgentTemplate> byId;

    private AgentTemplates(Map<String, AgentTemplate> byId) {
        this.byId = byId;
    }

    public static AgentTemplates load(String classpath) {
        try (InputStream in = AgentTemplates.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("模板文件不存在：" + classpath);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取模板失败：" + classpath, e);
        }
    }

    /**
     * 解析并校验。任何一处不合法都抛异常，不返回"半个目录"。
     *
     * @param md 文件全文
     * @throws IllegalStateException 缺字段、window 非法、段缺失、id 重复
     */
    public static AgentTemplates parse(String md) {
        if (md == null || md.isBlank()) {
            throw new IllegalStateException("模板文件是空的");
        }

        Map<String, Draft> drafts = new LinkedHashMap<>();
        Draft cur = null;
        StringBuilder buf = null;
        boolean inSystem = false;

        for (String raw : md.split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.startsWith("## ")) {
                String name = line.substring(3).trim();
                String lower = name.toLowerCase(Locale.ROOT);
                if (RESERVED.contains(lower)) {
                    if (cur == null) {
                        throw new IllegalStateException("`## " + name + "` 前面没有模板 id，无法归属");
                    }
                    inSystem = lower.equals("system");
                    buf = new StringBuilder();
                    if (inSystem) {
                        cur.system = buf;
                    } else {
                        cur.user = buf;
                    }
                } else {
                    if (name.isBlank()) {
                        throw new IllegalStateException("出现空的 `## ` 模板标题");
                    }
                    if (drafts.containsKey(name)) {
                        throw new IllegalStateException("模板 id 重复：" + name);
                    }
                    cur = new Draft(name);
                    drafts.put(name, cur);
                    buf = null;
                    inSystem = false;
                }
                continue;
            }

            if (cur == null) {
                // 文件开头的说明文字、一级标题：不参与解析
                continue;
            }
            if (buf != null) {
                // 模板之间的 `---` 分隔线不能混进提示词正文
                if (line.trim().equals("---")) {
                    continue;
                }
                buf.append(raw).append('\n');
            } else {
                cur.meta(line);
            }
        }

        if (drafts.isEmpty()) {
            throw new IllegalStateException("模板文件里没有任何 `## <id>` 段");
        }

        Map<String, AgentTemplate> out = new LinkedHashMap<>();
        for (Draft d : drafts.values()) {
            out.put(d.id, d.build());
        }
        return new AgentTemplates(out);
    }

    public Optional<AgentTemplate> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public List<AgentTemplate> all() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    // ---------------------------------------------------------------- 内部解析

    /** 解析期的可变容器。对外只暴露不可变的 {@link AgentTemplate}。 */
    private static final class Draft {
        private final String id;
        private String title;
        private boolean brief = true;
        private String window;
        private List<String> keywords = List.of();
        private int minHits;
        private List<String> topic = List.of();
        private int minTopicHits;
        private String expandTo;
        private String costHint = "";
        private StringBuilder system;
        private StringBuilder user;

        private Draft(String id) {
            this.id = id;
        }

        private void meta(String line) {
            String t = line.trim();
            if (t.isEmpty() || !t.contains(":")) {
                // 允许写注释行（不含冒号），不当作错误
                return;
            }
            int i = t.indexOf(':');
            String key = t.substring(0, i).trim().toLowerCase(Locale.ROOT);
            String val = t.substring(i + 1).trim();
            switch (key) {
                case "title" -> title = val;
                case "brief" -> brief = parseBool(val);
                case "window" -> window = val;
                case "keywords" -> keywords = parseList(val);
                case "min-hits" -> minHits = parseInt(key, val);
                case "topic" -> topic = parseList(val);
                case "min-topic-hits" -> minTopicHits = parseInt(key, val);
                case "expand-to" -> expandTo = val;
                case "cost-hint" -> costHint = val;
                default -> {
                    // 未知元数据忽略：允许给模板加备注字段而不改代码
                }
            }
        }

        private AgentTemplate build() {
            if (title == null || title.isBlank()) {
                throw new IllegalStateException("模板 " + id + " 缺 title");
            }
            if (window == null || window.isBlank()) {
                throw new IllegalStateException("模板 " + id + " 缺 window");
            }
            if (minHits < 1) {
                throw new IllegalStateException("模板 " + id + " 的 min-hits 必须 ≥1，实际 " + minHits);
            }
            // 写了下限却没写锚点词，等于这条校验永远不会触发 —— 那是配置错误，不是"关闭校验"
            if (minTopicHits > 0 && topic.isEmpty()) {
                throw new IllegalStateException(
                        "模板 " + id + " 写了 min-topic-hits 但没有 topic（要关掉主题校验就两行都别写）");
            }
            String sys = system == null ? "" : system.toString().trim();
            String usr = user == null ? "" : user.toString().trim();
            if (sys.isEmpty()) {
                throw new IllegalStateException("模板 " + id + " 缺 `## system` 段");
            }
            if (usr.isEmpty()) {
                throw new IllegalStateException("模板 " + id + " 缺 `## user` 段");
            }
            AgentTemplate t = new AgentTemplate(id, title, brief, window, keywords, minHits,
                    topic, minTopicHits, expandTo, costHint, sys, usr);
            // 立刻验一次 window，别等到第一次点击才发现模板写错。
            // windowSeconds 自己抛 IllegalArgumentException，这里统一包成 IllegalStateException：
            // 解析期的所有错误对外只能是同一种，否则每个调用方都要 catch 两个异常。
            try {
                t.windowSeconds();
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("模板 " + id + "：" + e.getMessage(), e);
            }
            return t;
        }

        private boolean parseBool(String val) {
            String v = val.trim().toLowerCase(Locale.ROOT);
            if (v.equals("true") || v.equals("yes") || v.equals("1")) {
                return true;
            }
            if (v.equals("false") || v.equals("no") || v.equals("0")) {
                return false;
            }
            throw new IllegalStateException("模板 " + id + " 的 brief 只能是 true/false，实际 [" + val + "]");
        }

        private int parseInt(String key, String val) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("模板 " + id + " 的 " + key + " 不是整数：[" + val + "]", e);
            }
        }

        /** {@code [a, b, c]} 或 {@code []}；也容忍漏写方括号的 {@code a, b}。 */
        private List<String> parseList(String val) {
            String v = val.trim();
            if (v.startsWith("[")) {
                v = v.substring(1);
            }
            if (v.endsWith("]")) {
                v = v.substring(0, v.length() - 1);
            }
            if (v.isBlank()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String part : v.split(",")) {
                String s = part.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }
    }
}
