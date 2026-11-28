package com.herc.clsnews.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.herc.clsnews.ai.LlmCredential;
import com.herc.clsnews.ai.LlmKey;
import com.herc.clsnews.config.AppProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * DeepSeek 实现，走 {@code POST /chat/completions}，单次、无 tools。
 *
 * <p>和 {@code DeepSeekAnalyzer} 有三处刻意不同，都是为了"一次花一万 token 的调用"该有的谨慎：
 * <ol>
 *   <li><b>超时比单条分析长一倍</b>：单条分析输入几百 token，主线输入一万，
 *       prefill 本身就要 2–4 秒，用 30 秒会把正常的慢请求判成失败 —— 而失败一次就是白花一万 token。</li>
 *   <li><b>429 也重试</b>：单条分析遇到限流重试没意义（用户再点一次就行），
 *       主线重试一次的价值很高（省掉让用户重新等一轮的体验损失）。</li>
 *   <li><b>不把 key 的记忆留在字段里之外的任何地方</b>：日志只打 mask 之后的值。</li>
 * </ol>
 *
 * <p>没有 tools 参数。理由见 {@link AgentLlm}。
 */
@Component
public class DeepSeekAgentLlm implements AgentLlm {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAgentLlm.class);

    /** 最多两次：一次失败后再来一次。再多就是拿钱换概率，而主线的成功率本来就不该靠重试。 */
    private static final int MAX_ATTEMPTS = 2;

    /** 重试前的等待。限流场景下这个间隔够渠道恢复，又不至于让用户觉得卡死。 */
    private static final long RETRY_BACKOFF_MS = 1_500;

    private final AppProps props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private final String apiKey;
    private final String keySource;
    private final String baseUrl;
    private final String model;

    public DeepSeekAgentLlm(AppProps props, ObjectMapper mapper, LlmKey llmKey) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        AppProps.Agent cfg = props.getAgent();
        AppProps.Analysis ana = props.getAnalysis();

        // 供应商两项留空即继承单条分析的配置：同一家供应商不该配两遍，
        // 换中转站时也少一个"改了这处忘了那处"的机会
        this.model = firstNonBlank(cfg.getModel(), ana.getModel(), "deepseek-flash");
        this.baseUrl = firstNonBlank(cfg.getBaseUrl(), ana.getBaseUrl(), "https://api.deepseek.com");

        // key 不在这里解析：LlmKey 启动时解析一次，单条分析用的是同一个结果。
        // 这里只问一句"这把 key 能不能打到我要打的地址"。
        LlmCredential.Resolved cred = llmKey.forBaseUrl(this.baseUrl);
        this.apiKey = cred == null ? null : cred.value();
        this.keySource = cred == null ? "未配置" : cred.source();

        if (this.apiKey == null) {
            // 不再写"单条分析不受影响"：key 现在全站共用一份，拿不到就是两边都没有
            log.error("今日主线不可用（采集照常）：base-url={} 没有可用的 key，来源见上面「模型 key」那行",
                    this.baseUrl);
        } else {
            log.info("主线模型就绪：model={} baseUrl={} key={}…（来自 {}）",
                    this.model, this.baseUrl, LlmCredential.mask(this.apiKey), this.keySource);
        }
    }

    @Override
    public Reply chat(String system, String user) throws Exception {
        if (apiKey == null) {
            throw new IllegalStateException("API key 未配置，无法生成主线");
        }
        AppProps.Agent cfg = props.getAgent();
        String payload = buildPayload(cfg, system, user, false);

        HttpResponse<String> resp = null;
        Exception last = null;
        long started = System.currentTimeMillis();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            CompletableFuture<HttpResponse<String>> future = null;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                        .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();

                // sendAsync + 自己 get(timeout)：机器休眠时 HttpRequest.timeout 可能不生效，
                // send() 会永久挂住并把 Tomcat 工作线程一起拖死（单条分析踩过这个坑）
                future = http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                resp = future.get(cfg.getTimeoutMs() + 5_000L, TimeUnit.MILLISECONDS);

                if (resp.statusCode() == 200) {
                    break;
                }
                if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                    last = new IllegalStateException(describe(resp.statusCode(), resp.body()));
                } else {
                    // 401/402/403/404 重试没有意义：key 错、余额不足、模型无权限，重试一百次也一样
                    throw new IllegalStateException(describe(resp.statusCode(), resp.body()));
                }
            } catch (TimeoutException e) {
                if (future != null) {
                    future.cancel(true);
                }
                // 超时不重试：一次超时已经说明这次预填就压满了窗口，再来一次只会再花一万 token
                throw new IllegalStateException("调用模型超时（" + cfg.getTimeoutMs() + "ms 未返回）", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                last = cause instanceof Exception ex ? ex : new IllegalStateException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("主线第 {} 次调用失败（{}），{}ms 后重试", attempt, last.getMessage(), RETRY_BACKOFF_MS);
                Thread.sleep(RETRY_BACKOFF_MS);
            }
        }

        if (resp == null || resp.statusCode() != 200) {
            throw last != null ? last : new IllegalStateException("调用模型失败");
        }

        long latency = System.currentTimeMillis() - started;
        return parse(resp.body(), latency);
    }

    /**
     * 流式调用：正文攒满一次性给，思考过程逐段回调。
     *
     * <p><b>为什么不重试</b>：{@link #chat} 能重试是因为「一次失败 = 什么都没给出去」。
     * 流式已经把思考推了几十段给前端，半路失败再重发，前端看到的是「思考重头再来一遍」——
     * 那比直接报错更糟。所以这里一次失败即失败，与 {@code chat} 里「超时不重试」是同一个理由。
     *
     * <p><b>超时语义</b>：{@code HttpRequest.timeout} 对 {@code BodyHandlers.ofInputStream}
     * 的流式读取不生效（流建立后一直有字节进出），所以超时靠外层 {@code future.get} 拉不动、
     * 只能自己在前端/上层兜。这里退而求其次：整体不设硬超时，交给调用方的总闸
     * （{@code askStream} 的 emitter 超时）去掐。
     */
    @Override
    public Reply chatStream(String system, String user, Consumer<String> onReasoning) throws Exception {
        if (apiKey == null) {
            throw new IllegalStateException("API key 未配置，无法生成主线");
        }
        AppProps.Agent cfg = props.getAgent();
        String payload = buildPayload(cfg, system, user, true);
        long started = System.currentTimeMillis();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                .timeout(Duration.ofMillis(cfg.getTimeoutMs() + 30_000L))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            // 非 200 时 body 是普通 JSON（不是 SSE），按老逻辑读错误
            String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException(describe(resp.statusCode(), body));
        }

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        int tokensIn = 0;
        int tokensOut = 0;
        int reasoningTokens = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith(":")) {
                    continue; // 心跳注释行 / 空行
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode node = mapper.readTree(data);
                JsonNode delta = node.path("choices").path(0).path("delta");

                JsonNode rc = delta.path("reasoning_content");
                if (rc.isTextual() && !rc.asText().isEmpty()) {
                    reasoning.append(rc.asText());
                    if (onReasoning != null) {
                        onReasoning.accept(rc.asText());
                    }
                }
                JsonNode c = delta.path("content");
                if (c.isTextual() && !c.asText().isEmpty()) {
                    content.append(c.asText());
                }

                // 最后一个 chunk（开了 include_usage）会带 usage
                JsonNode usage = node.path("usage");
                if (usage.isObject() && usage.path("prompt_tokens").isInt()) {
                    tokensIn = usage.path("prompt_tokens").asInt();
                    tokensOut = usage.path("completion_tokens").asInt();
                    JsonNode rt = usage.path("completion_tokens_details").path("reasoning_tokens");
                    reasoningTokens = rt.isInt() ? rt.asInt() : 0;
                }
            }
        }

        long latency = System.currentTimeMillis() - started;
        String text = content.toString();
        if (text.isBlank()) {
            // 与 parse 里同样的判据：额度全被思考吃光，正文一个字没留
            throw new LlmCallException("模型返回空内容（思考 " + reasoningTokens + " token 后正文为空），"
                    + "调大 app.agent.max-tokens-out", tokensIn, tokensOut);
        }
        return new Reply(text, tokensIn, tokensOut, model, latency,
                reasoning.length() == 0 ? null : reasoning.toString(), reasoningTokens);
    }

    private String buildPayload(AppProps.Agent cfg, String system, String user, boolean stream) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", cfg.getTemperature());
        body.put("max_tokens", cfg.getMaxTokensOut());
        // 强制 JSON：不加这个，模型经常在 JSON 前面写一句"好的，以下是今日主线"
        body.putObject("response_format").put("type", "json_object");
        if (stream) {
            // 思考过程只要「低强度」就够：用户要看的是「它在想什么」，不是「它想得最周全」。
            // 高 effort 会把 14 秒拉到 30 秒+、多花成倍的推理 token，对一段折叠着看的文字不划算。
            body.put("stream", true);
            body.put("reasoning_effort", "low");
            // usage 只在最后一个 chunk 里给，且必须显式开这个开关 —— 不开就分不清
            // reasoning token 和正文 token，思考那一步的账就记不了
            body.putObject("stream_options").put("include_usage", true);
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        return mapper.writeValueAsString(body);
    }

    private Reply parse(String responseBody, long latency) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode usage = root.path("usage");
        int in = usage.path("prompt_tokens").asInt(0);
        int out = usage.path("completion_tokens").asInt(0);

        JsonNode choice = root.path("choices").path(0);
        String finish = choice.path("finish_reason").asText("");
        String content = choice.path("message").path("content").asText("");

        if (content.isBlank()) {
            // 常见原因：max_tokens 被推理过程吃光，一个字都没留给正文（deepseek-flash 是推理模型）
            throw new LlmCallException("模型返回空内容（finish_reason=" + finish
                    + "，completion_tokens=" + out + "），调大 app.agent.max-tokens-out", in, out);
        }
        if ("length".equals(finish)) {
            // 截断的 JSON 一定解析失败。提前给出可读原因，而不是让解析器报"unexpected end"。
            // 带 usage 抛：这次的钱已经花了，账上必须看得见（实测第一次验收就是这样花了 1 万 token）
            throw new LlmCallException("输出被 max_tokens 截断（" + out + " tokens，输入 " + in
                    + "），调大 app.agent.max-tokens-out", in, out);
        }
        String modelName = root.path("model").asText(model);
        return new Reply(content, in, out, modelName, latency);
    }

    private String describe(int status, String body) {
        return switch (status) {
            case 401 -> "API key 无效（401）：检查 app.agent.api-key / DEEPSEEK_API_KEY / ~/.codex/auth.json";
            case 402 -> "余额不足（402），去控制台充值后再试";
            case 403 -> "key 没有该模型权限（403）：" + model;
            case 404 -> "接口路径不对（404），检查 app.agent.base-url";
            case 429 -> "触发限流（429）：主线单价高，把 app.agent.daily-cap 调小或稍后再试";
            default -> "模型返回 HTTP " + status + "：" + clip(body, 200);
        };
    }

    @Override
    public boolean ready() {
        return apiKey != null;
    }

    @Override
    public String modelName() {
        return model;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
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
