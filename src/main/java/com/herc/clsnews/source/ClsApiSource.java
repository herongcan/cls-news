package com.herc.clsnews.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.model.RawItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 财联社电报接口。
 *
 * 实测（2026-11）三条硬事实，改代码前先看：
 *  1. rn 最大 50，传 100 会返回空数组；
 *  2. last_time / last_id / id 等游标参数全部被服务端忽略，永远只给「最新 50 条」，
 *     所以去重必须在本地做（item_id 唯一索引），不能指望服务端增量；
 *  3. 签名 = md5( sha1( 按 key 排序后的 queryString ) )，sign 不参与签名计算。
 *
 * 因为拿不到历史分页，首屏条数是「随着运行时间自然累积」的，
 * 页面标题里会如实显示当前已收录多少条。
 */
@Component
@ConditionalOnProperty(name = "app.source", havingValue = "cls-api", matchIfMissing = true)
public class ClsApiSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(ClsApiSource.class);
    private static final String ENDPOINT = "https://www.cls.cn/v1/roll/get_roll_list";
    private static final int PAGE_SIZE = 50;

    private final AppProps props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ClsApiSource(AppProps props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getHttp().getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "cls.cn /v1/roll/get_roll_list";
    }

    @Override
    public List<RawItem> fetch() throws Exception {
        String query = buildQuery();
        String url = ENDPOINT + "?" + query + "&sign=" + sign(query);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", props.getHttp().getUserAgent())
                .header("Referer", props.getHttp().getReferer())
                .header("Accept", "application/json, text/plain, */*")
                .timeout(Duration.ofMillis(props.getHttp().getTimeoutMs()))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + ENDPOINT);
        }

        JsonNode root = mapper.readTree(resp.body());
        int errno = root.path("errno").asInt(-1);
        if (errno != 0) {
            throw new IOException("errno=" + errno + " msg=" + root.path("msg").asText());
        }

        JsonNode arr = root.path("data").path("roll_data");
        List<RawItem> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                RawItem item = toItem(n);
                if (item != null) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    private String buildQuery() {
        // 必须按 key 排序，sign 是基于这个顺序算的
        Map<String, String> p = new TreeMap<>();
        p.put("app", "CailianpressWeb");
        p.put("category", "");
        p.put("last_time", "");
        p.put("os", "web");
        p.put("rn", String.valueOf(PAGE_SIZE));
        p.put("sv", "8.4.6");
        return p.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private RawItem toItem(JsonNode n) {
        long id = n.path("id").asLong(0);
        if (id <= 0) {
            return null;
        }

        String content = n.path("content").asText("");
        String brief = n.path("brief").asText("");
        String title = n.path("title").asText("");

        if (content.isBlank()) {
            content = brief;
        }
        if (content.isBlank()) {
            return null;
        }
        if (title.isBlank()) {
            // 电报多数没有独立标题，用正文首句当标题，便于列表扫读
            title = firstClause(content);
        }

        return new RawItem(
                String.valueOf(id),
                title,
                content,
                "https://www.cls.cn/detail/" + id,
                n.path("ctime").asLong(0),
                n.path("level").asText(""),
                stocks(n.path("stock_list")));
    }

    /** stock_list 是来源自带的关联股票，正好可以喂给后面的分析环节，省一次识别。 */
    private String stocks(JsonNode list) {
        if (!list.isArray() || list.isEmpty()) {
            return "[]";
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode s : list) {
            String name = s.path("name").asText("");
            String code = s.path("StockID").asText("");
            if (!name.isBlank()) {
                out.add(Map.of("name", name, "code", code));
            }
        }
        try {
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String firstClause(String text) {
        String t = text.replaceFirst("^财联社\\d+月\\d+日电[，,]", "").trim();
        int cut = -1;
        for (char c : new char[]{'。', '；', ';'}) {
            int i = t.indexOf(c);
            if (i > 0 && (cut < 0 || i < cut)) {
                cut = i;
            }
        }
        t = cut > 0 ? t.substring(0, cut) : t;
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String sign(String query) {
        return md5Hex(sha1Hex(query));
    }

    private static String sha1Hex(String s) {
        return hex(digest("SHA-1", s));
    }

    private static String md5Hex(String s) {
        return hex(digest("MD5", s));
    }

    private static byte[] digest(String alg, String s) {
        try {
            return MessageDigest.getInstance(alg).digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(alg + " 不可用", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
