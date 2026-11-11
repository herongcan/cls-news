package com.herc.clsnews.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.model.News;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 classpath 的 prompts/analyze-news.md 里读出 System / User 两段，并按 {{占位符}} 渲染。
 *
 * 为什么不把提示词写成 Java 字符串：调提示词是这个项目里最高频的操作，
 * 放在资源文件里改完重启即可，不用重新编译，也不用操心转义。
 *
 * 解析规则：以 {@code ## System} 和 {@code ## User} 开头的二级标题作为一个段，
 * 到下一个二级标题为止。其余章节（比如「与代码的对应关系」）只是给人看的，不参与渲染。
 */
final class PromptTemplate {

    private static final Pattern SECTION =
            Pattern.compile("^##\\s+(\\S+)\\s*$(.*?)(?=^##\\s|\\z)", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    /** 单条快讯正文最多送这么多字，超出截断 —— 快讯一般 300 字内，这个上限只是防止异常长文烧钱。 */
    private static final int MAX_CONTENT_CHARS = 1500;

    private final String system;
    private final String user;

    private PromptTemplate(String system, String user) {
        this.system = system;
        this.user = user;
    }

    static PromptTemplate load(String classpath) {
        String md;
        try (InputStream in = PromptTemplate.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("提示词文件不存在：" + classpath);
            }
            md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词失败：" + classpath, e);
        }

        String system = null;
        String user = null;
        Matcher m = SECTION.matcher(md);
        while (m.find()) {
            String name = m.group(1).toLowerCase();
            if (name.equals("system")) {
                system = m.group(2).trim();
            } else if (name.equals("user")) {
                user = m.group(2).trim();
            }
        }
        if (system == null || user == null) {
            throw new IllegalStateException("提示词文件缺少 `## System` 或 `## User` 段：" + classpath);
        }
        return new PromptTemplate(system, user);
    }

    String system() {
        return system;
    }

    String render(News news) {
        String raw = news.content() == null ? "" : news.content();
        final String content = raw.length() > MAX_CONTENT_CHARS ? raw.substring(0, MAX_CONTENT_CHARS) : raw;
        String body = PLACEHOLDER.matcher(user).replaceAll(r -> switch (r.group(1)) {
            case "publishTime" -> Matcher.quoteReplacement(TIME_FMT.format(Instant.ofEpochSecond(news.publishTime())));
            case "level" -> Matcher.quoteReplacement(news.level() == null || news.level().isBlank() ? "C" : news.level());
            case "stocks" -> Matcher.quoteReplacement(stocksText(news.stocksJson()));
            case "content" -> Matcher.quoteReplacement(content);
            default -> Matcher.quoteReplacement(r.group());
        });
        // 未识别的占位符原样留着，肉眼就能看出来提示词和代码对不上
        return body;
    }

    /** 把 [{"name":"坤彩科技","code":"sh603826"}] 摊平成「坤彩科技(sh603826)」，比塞 JSON 更省 token 也更好读。 */
    private String stocksText(String stocksJson) {
        if (stocksJson == null || stocksJson.isBlank() || "[]".equals(stocksJson)) {
            return "（原文未标注个股）";
        }
        try {
            JsonNode arr = new ObjectMapper().readTree(stocksJson);
            List<String> out = new ArrayList<>();
            for (JsonNode s : arr) {
                String name = s.path("name").asText("");
                String code = s.path("code").asText("");
                if (name.isBlank() && code.isBlank()) {
                    continue;
                }
                out.add(code.isBlank() ? name : name + "(" + code + ")");
            }
            return out.isEmpty() ? "（原文未标注个股）" : String.join("、", out);
        } catch (Exception e) {
            return stocksJson;
        }
    }
}
