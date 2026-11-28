package com.herc.clsnews.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * key 的解析规则，全项目只此一份。
 *
 * <p><b>为什么必须抽出来</b>：这个项目有一个刻意的习惯 ——
 * <b>换令牌只改一个文件</b>（{@code ~/.codex/auth.json}）。
 * 一旦「单条分析」和「今日主线」各写一份解析逻辑，某天改了取值顺序或环境变量名，
 * 必然只改一处，表现就是"分析能用、主线 401"，而两边的代码看起来都对。
 *
 * <p>取值顺序：配置项 → 环境变量 → {@code ~/.codex/auth.json}。
 * 第三顺位有前置条件：{@code base-url} 必须指向 deepseek，
 * 否则会把别的厂商的 key 发过去 —— 那是比 401 更糟的事（泄露）。
 */
public final class LlmCredential {

    /** 环境变量名。中转站场景下也是这个名字，改这里就够了。 */
    public static final String ENV = "DEEPSEEK_API_KEY";

    private LlmCredential() {
    }

    /**
     * @param value  key 本体，可能为 null
     * @param source 从哪读到的，写进启动日志用
     */
    public record Resolved(String value, String source) {
        public boolean present() {
            return value != null && !value.isBlank();
        }
    }

    /**
     * 找 key。<b>找不到返回 null 而不是抛异常</b> ——
     * 由调用方决定这是致命错误（单条分析：功能不可用）还是可降级（主线：返回 503），
     * 用异常表达"没找到"会逼着每个调用方写 try/catch 才能降级。
     *
     * @param configKey application.yml 里的 app.*.api-key，可为空
     * @param baseUrl   用于判断能否读 codex 的 auth.json
     * @param mapper    JSON 解析器
     * @return null 表示三处都没有
     */
    public static Resolved find(String configKey, String baseUrl, ObjectMapper mapper) {
        if (configKey != null && !configKey.isBlank()) {
            return new Resolved(configKey.trim(), "application.yml");
        }
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            return new Resolved(env.trim(), "环境变量 " + ENV);
        }
        if (baseUrl == null || !baseUrl.contains("deepseek")) {
            // 换了中转站还去读 auth.json，等于把 DeepSeek 官方 key 发给第三方
            return null;
        }
        Path auth = Path.of(System.getProperty("user.home"), ".codex", "auth.json");
        if (!Files.isReadable(auth)) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(Files.readString(auth));
            String k = n.path("OPENAI_API_KEY").asText("");
            if (k.startsWith("sk-")) {
                return new Resolved(k.trim(), auth.toString());
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** 日志里只露头尾，中间的字符不该出现在任何地方（包括 issue 截图）。 */
    public static String mask(String key) {
        if (key == null || key.length() < 12) {
            return "***";
        }
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }

    /** 没配 key 时给运维看的操作指引。 */
    public static String hint() {
        return """
                没有找到 API key。三选一：
                  1) 启动时加 --app.analysis.api-key=sk-xxx
                  2) export DEEPSEEK_API_KEY=sk-xxx
                  3) 保证 ~/.codex/auth.json 里有 OPENAI_API_KEY（且 base-url 指向 deepseek）""";
    }
}
