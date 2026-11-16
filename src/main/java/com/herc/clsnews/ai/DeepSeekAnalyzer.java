package com.herc.clsnews.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.model.News;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * DeepSeek 实现。开了 {@code app.analysis.provider=deepseek} 才会创建。
 *
 * 三件事是这个类唯一值得看的地方：
 * <ol>
 *   <li><b>提示词不在代码里</b>：从 prompts/analyze-news.md 读，改完重启即可，不用重新编译。</li>
 *   <li><b>模型输出不能直接信</b>：sentiment 只认三个值，confidence 越界就夹住，
 *       code 按市场白名单校验（认不出的丢掉代码、保留名称）。
 *       这里<b>不再</b>把低置信度降级成 neutral —— 那条规则和提示词里的同款互相强化，
 *       实测让 60% 的结论退化成「信息不足」。</li>
 *   <li><b>失败要能看懂</b>：401/402/429 各自给出人话解释，而不是丢一个 JSON 原文让人猜。</li>
 * </ol>
 *
 * key 的取值顺序：app.analysis.api-key → 环境变量 DEEPSEEK_API_KEY → ~/.codex/auth.json。
 * 第三顺位是为了「换令牌只改一个文件」这个习惯 —— 前提是 base-url 指向 deepseek，
 * 避免误把其他厂商的 key 发过去。
 */
@Component
@ConditionalOnProperty(prefix = "app.analysis", name = "provider", havingValue = "deepseek")
public class DeepSeekAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAnalyzer.class);

    private static final String PROMPT_RESOURCE = "prompts/analyze-news.md";
    private static final Set<String> DIRECTIONS = Set.of("bullish", "neutral", "bearish");
    private static final int MAX_ATTEMPTS = 2;

    /** 三市场的代码形态。认不出的一律丢弃代码、保留名称，不丢整条记录。 */
    private static final Pattern CN_CODE = Pattern.compile("^(sh|sz|bj)\\d{6}$");
    private static final Pattern HK_CODE = Pattern.compile("^hk\\d{4,5}$");
    private static final Pattern HK_BARE = Pattern.compile("^\\d{4,5}$");
    private static final Pattern US_CODE = Pattern.compile("^[A-Z]{1,5}(\\.[A-Z]{1,2})?$");

    /** 模型自造的代码占位词。库里实测出现过「未标注」×4、OX、87、15、03、02。 */
    private static final Set<String> CODE_NOISE = Set.of("未标注", "未标", "未知", "无", "-", "null");

    private final AppProps props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final PromptTemplate prompt;

    private final String apiKey;
    private final String keySource;

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong tokensIn = new AtomicLong();
    private final AtomicLong tokensOut = new AtomicLong();

    public DeepSeekAnalyzer(AppProps props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.prompt = PromptTemplate.load(PROMPT_RESOURCE);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        AppProps.Analysis cfg = props.getAnalysis();

        // 找不到 key 时不抛异常，降级成「分析不可用，采集照常」。
        // 这是部署时最容易犯的错（.env 忘了填、填错、挂卷没生效），如果让它把关整个 context，
        // 结果是容器反复重启、连新闻都看不了 —— 而采集才是这个工具的本体，分析只是增强。
        // 快速失败在这里是错的：一行日志比一个起不来的服务有用得多。
        String key = null;
        String from = "未配置";
        try {
            Resolved r = resolveKey(cfg);
            key = r.value();
            from = r.source();
        } catch (IllegalStateException e) {
            log.error("分析功能不可用（采集不受影响）：{}", e.getMessage().lines().findFirst().orElse(""));
        }
        this.apiKey = key;
        this.keySource = from;

        if (key == null) {
            log.error("DeepSeekAnalyzer 已创建但无 key，所有分析会直接失败。补齐 key 后重启即可。");
        } else {
            log.info("DeepSeekAnalyzer 就绪：model={} baseUrl={} key={}…（来自 {}）",
                    cfg.getModel(), cfg.getBaseUrl(), mask(key), from);
        }
    }

    @Override
    public AnalysisResult analyze(News news) throws Exception {
        if (apiKey == null) {
            throw new IllegalStateException("DeepSeek key 未配置，无法分析（采集正常）");
        }
        AppProps.Analysis cfg = props.getAnalysis();
        String payload = buildPayload(cfg, prompt.system(), prompt.render(news));

        HttpResponse<String> resp = null;
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            java.util.concurrent.CompletableFuture<HttpResponse<String>> future = null;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(trimSlash(cfg.getBaseUrl()) + "/chat/completions"))
                        .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();

                // 用 sendAsync + 自己 get(timeout) 而不是 send()：
                // 实测机器休眠时，HttpRequest.timeout 可能失效，send() 会永久挂住，
                // 进而把整个调度线程拖死（采集也一起停）。这里加一道外层的硬超时兜底，
                // 超时就 cancel 掉底层请求，保证这个线程一定会被放回来。
                future = http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                resp = future.get(cfg.getTimeoutMs() + 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (resp.statusCode() == 200) {
                    break;
                }
                // 4xx 重试没有意义（key 错、余额不足、参数错），5xx 才重试一次
                if (resp.statusCode() < 500) {
                    throw new IllegalStateException(describe(resp.statusCode(), resp.body()));
                }
                last = new IllegalStateException(describe(resp.statusCode(), resp.body()));
            } catch (java.util.concurrent.TimeoutException e) {
                if (future != null) {
                    future.cancel(true);
                }
                last = new IllegalStateException("调用 DeepSeek 超时（" + cfg.getTimeoutMs() + "ms 未返回）");
            } catch (java.util.concurrent.ExecutionException e) {
                // 网络异常被 CompletableFuture 包了一层，取 cause 才是真实原因
                Throwable cause = e.getCause() == null ? e : e.getCause();
                last = cause instanceof Exception ex ? ex : new IllegalStateException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("第 {} 次调用失败（{}），1.5 秒后重试", attempt, last.getMessage());
                Thread.sleep(1_500);
            }
        }
        if (resp == null || resp.statusCode() != 200) {
            throw last != null ? last : new IllegalStateException("调用 DeepSeek 失败");
        }

        return parse(resp.body(), cfg.getModel(), news);
    }

    private String buildPayload(AppProps.Analysis cfg, String system, String user) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("temperature", cfg.getTemperature());
        body.put("max_tokens", cfg.getMaxTokens());
        // 强制 JSON 输出：不加这个，模型偶尔会在 JSON 前后写一句「好的，分析如下」
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        return mapper.writeValueAsString(body);
    }

    private AnalysisResult parse(String responseBody, String fallbackModel, News news) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode usage = root.path("usage");
        long in = usage.path("prompt_tokens").asLong(0);
        long out = usage.path("completion_tokens").asLong(0);
        calls.incrementAndGet();
        tokensIn.addAndGet(in);
        tokensOut.addAndGet(out);

        String model = root.path("model").asText(fallbackModel);
        JsonNode choice = root.path("choices").path(0);
        String finish = choice.path("finish_reason").asText("");
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank()) {
            // 常见原因：max_tokens 被推理过程吃光，一个字都没留给正文
            throw new IllegalStateException("模型返回了空内容（finish_reason=" + finish
                    + "，completion_tokens=" + out + "），调大 app.analysis.max-tokens");
        }
        if ("length".equals(finish)) {
            throw new IllegalStateException("输出被 max_tokens 截断（" + out
                    + " tokens），JSON 不完整，调大 app.analysis.max-tokens");
        }

        JsonNode j;
        try {
            j = mapper.readTree(stripFence(content));
        } catch (Exception e) {
            throw new IllegalStateException("返回内容不是合法 JSON：" + clip(content, 160), e);
        }

        AnalysisResult r = normalize(j, model, sourceCodes(news.stocksJson()));
        log.info("分析完成 model={} sentiment={} conf={} tokens={}+{}", model, r.sentiment(),
                r.confidence(), in, out);
        return r;
    }

    /** 模型偶尔会包一层 ```json，去掉；正常情况原样返回。 */
    private String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstLine = t.indexOf('\n');
            if (firstLine > 0) {
                t = t.substring(firstLine + 1);
            }
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        return t.trim();
    }

    private AnalysisResult normalize(JsonNode j, String model, Map<String, String> sourceCodes) {
        String sentiment = j.path("sentiment").asText("").toLowerCase(Locale.ROOT);
        if (!DIRECTIONS.contains(sentiment)) {
            log.warn("sentiment 非法值 [{}]，按 neutral 处理", sentiment);
            sentiment = "neutral";
        }

        double confidence = j.path("confidence").asDouble(0.5);
        confidence = Math.max(0, Math.min(1, confidence));
        confidence = Math.round(confidence * 100) / 100.0;
        // 这里原本有个「confidence < 0.5 一律降 neutral」的兜底，和提示词第 3 条互相强化：
        // 模型倾向报低置信 → 全被压成中性 → 摘要只剩「信息不足」。
        // 方向判断归模型，置信度只表达把握大小，不再改写结论。

        // 提示词要求 40 字以内，但模型经常写到 60~80 字。这里给到 100 是为了不被截成半句
        //（实测「…对离岸架构避税的吸引」这种断头话，比长一点难看得多）
        String summary = clip(j.path("summary").asText(""), 100);
        if (summary.isBlank()) {
            // 不再兜底成「信息不足」：那是没有信息量的模板话，还会污染评测统计
            log.warn("模型没给 summary，结论 [{}]", sentiment);
        }

        String inference = clip(j.path("inference").asText(""), 120);

        List<String> sectors = new ArrayList<>();
        for (JsonNode s : j.path("sectors")) {
            String v = clip(s.asText(""), 12);
            if (!v.isBlank() && !sectors.contains(v)) {
                sectors.add(v);
            }
            if (sectors.size() >= 6) {
                break;
            }
        }

        List<AnalysisResult.StockImpact> stocks = new ArrayList<>();
        for (JsonNode s : j.path("stocks")) {
            String rawCode = clip(s.path("code").asText(""), 20);
            String name = clip(s.path("name").asText(""), 20);
            if (rawCode.isBlank() && name.isBlank()) {
                continue;
            }

            // 代码前缀比模型自报的 market 可靠（「market 写美股、代码却是 sh600362」实测出现过）
            String byCode = marketOf("", rawCode);
            String market = byCode.isBlank() ? marketOf(s.path("market").asText(""), "") : byCode;
            String code = canonicalCode(market, rawCode);
            if (code.isBlank() && !rawCode.isBlank()) {
                log.debug("丢弃非法代码 [{}]（{}）", rawCode, name);
            }

            // 模型会编股票代码（实测把新易盛写成 sz300500，真值是 sz300502）。
            // 财联社原文的 stock_list 带权威代码，名字对得上就以来源为准。
            String truth = sourceCodes.get(name);
            if (truth != null && !truth.equalsIgnoreCase(code)) {
                if (!code.isBlank()) {
                    log.debug("股票代码纠正 {}：模型给 {}，来源为 {}", name, code, truth);
                }
                code = truth.toLowerCase(Locale.ROOT);
                market = "A股";
            }
            if (code.isBlank() && name.isBlank()) {
                continue;
            }

            String impact = s.path("impact").asText("").toLowerCase(Locale.ROOT);
            if (!DIRECTIONS.contains(impact)) {
                impact = "neutral";
            }
            stocks.add(new AnalysisResult.StockImpact(market, code, name, impact,
                    clip(s.path("reason").asText(""), 60)));
            if (stocks.size() >= 8) {
                break;
            }
        }
        return new AnalysisResult(sentiment, confidence, summary, inference, sectors, stocks, model);
    }

    /** 板块/市场标签 → 标准三值；认不出返回空串，由调用方决定回退。 */
    private String marketOf(String raw, String code) {
        String m = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (m.contains("港股") || m.contains("香港") || m.equals("hk")) {
            return "港股";
        }
        if (m.contains("美股") || m.contains("美国") || m.equals("us")) {
            return "美股";
        }
        if (m.contains("a股") || m.contains("沪深") || m.contains("中国") || m.equals("cn")) {
            return "A股";
        }
        String c = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("sh") || c.startsWith("sz") || c.startsWith("bj")) {
            return "A股";
        }
        if (c.startsWith("hk")) {
            return "港股";
        }
        return "";
    }

    /**
     * 按市场校验代码格式，认不出就返回空串（保留名称，不丢整条）。
     * 库里实测有 9 条脏代码：「未标注」×4、OX、87、15、03、02 —— 全是模型自造的占位。
     */
    private String canonicalCode(String market, String raw) {
        if (raw == null) {
            return "";
        }
        String c = raw.trim();
        if (c.isEmpty()) {
            return "";
        }
        String lower = c.toLowerCase(Locale.ROOT);
        // 只允许字母数字和点，「未标注」这类中文占位直接出局
        if (CODE_NOISE.contains(lower) || !lower.matches("[a-z0-9.]+")) {
            return "";
        }
        return switch (market) {
            case "A股" -> CN_CODE.matcher(lower).matches() ? lower : "";
            case "港股" -> {
                if (HK_CODE.matcher(lower).matches()) {
                    yield lower;
                }
                yield HK_BARE.matcher(lower).matches() ? "hk" + lower : "";
            }
            case "美股" -> {
                String u = lower.toUpperCase(Locale.ROOT);
                if (u.startsWith("US") && u.length() > 2) {
                    u = u.substring(2);
                }
                yield US_CODE.matcher(u).matches() ? u : "";
            }
            default -> {
                // market 缺失时只认代码前缀自证的 A 股/港股。
                // 纯字母不做美股推断 —— 否则模型写的「OX」会被当成真 ticker 存进去。
                if (CN_CODE.matcher(lower).matches()) {
                    yield lower;
                }
                if (HK_CODE.matcher(lower).matches()) {
                    yield lower;
                }
                yield HK_BARE.matcher(lower).matches() ? "hk" + lower : "";
            }
        };
    }

    /** 来源 stock_list 里的 名称 → 代码，用于纠正模型编造的代码。 */
    private Map<String, String> sourceCodes(String stocksJson) {
        if (stocksJson == null || stocksJson.isBlank() || "[]".equals(stocksJson)) {
            return Map.of();
        }
        try {
            JsonNode arr = mapper.readTree(stocksJson);
            Map<String, String> map = new HashMap<>();
            for (JsonNode s : arr) {
                String name = s.path("name").asText("");
                String code = s.path("code").asText("");
                if (!name.isBlank() && !code.isBlank()) {
                    map.put(name, code);
                }
            }
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String describe(int status, String body) {
        return switch (status) {
            case 401 -> "DeepSeek key 无效（401）：检查 app.analysis.api-key、环境变量 DEEPSEEK_API_KEY "
                    + "或 ~/.codex/auth.json";
            case 402 -> "DeepSeek 余额不足（402），去控制台充值后再开分析";
            case 403 -> "DeepSeek 拒绝访问（403）：key 没有该模型权限";
            case 404 -> "接口路径不对（404），检查 app.analysis.base-url";
            case 429 -> "触发限流（429）：把 analysis.daily-cap 调小，或稍后重试";
            default -> "DeepSeek 返回 HTTP " + status + "：" + clip(body, 200);
        };
    }

    @Override
    public String modelName() {
        return props.getAnalysis().getModel();
    }

    /** key 没配好时这里返回 false，/api/status 能直接看出来。 */
    @Override
    public boolean ready() {
        return apiKey != null;
    }

    /** 给 /api/status 用：一共调了多少次、烧了多少 token。 */
    @Override
    public String usage() {
        return "calls=" + calls.get() + " tokens=" + (tokensIn.get() + tokensOut.get());
    }

    private record Resolved(String value, String source) {
    }

    private Resolved resolveKey(AppProps.Analysis cfg) {
        String fromCfg = cfg.getApiKey();
        if (fromCfg != null && !fromCfg.isBlank()) {
            return new Resolved(fromCfg.trim(), "application.yml");
        }
        String env = System.getenv("DEEPSEEK_API_KEY");
        if (env != null && !env.isBlank()) {
            return new Resolved(env.trim(), "环境变量 DEEPSEEK_API_KEY");
        }
        if (cfg.getBaseUrl() != null && cfg.getBaseUrl().contains("deepseek")) {
            Path auth = Path.of(System.getProperty("user.home"), ".codex", "auth.json");
            if (Files.isReadable(auth)) {
                try {
                    JsonNode n = mapper.readTree(Files.readString(auth));
                    String k = n.path("OPENAI_API_KEY").asText("");
                    if (k.startsWith("sk-")) {
                        return new Resolved(k.trim(), auth.toString());
                    }
                } catch (Exception e) {
                    log.warn("读取 {} 失败：{}", auth, e.getMessage());
                }
            }
        }
        throw new IllegalStateException("""
                没有找到 DeepSeek key。三选一：
                  1) 启动时加 --app.analysis.api-key=sk-xxx
                  2) export DEEPSEEK_API_KEY=sk-xxx
                  3) 保证 ~/.codex/auth.json 里有 OPENAI_API_KEY""");
    }

    private static String mask(String key) {
        if (key == null || key.length() < 12) {
            return "***";
        }
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
