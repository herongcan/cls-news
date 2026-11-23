package com.herc.clsnews.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.herc.clsnews.support.ApiHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code analysis_json} 的解析与兜底。
 *
 * <p>这一列存的是大模型原样输出：可能是合法 JSON、可能少字段、也可能是
 * 半截被截断的串（改过 max-tokens 就会遇到）。解析失败如果往外抛，
 * 表现是「整页 500」或者「列表里某一条永远渲染不出来」—— 一条坏数据毁掉整屏，
 * 所以必须在边界处吞掉并且只影响这一条。
 */
class AnalysisJsonTest {

    @TempDir
    Path dir;

    private ApiHarness api;

    @BeforeEach
    void setUp() throws Exception {
        api = ApiHarness.create(dir);
    }

    @Test
    @DisplayName("脏 JSON 当作「没分析」，接口仍然 200")
    void dirtyJsonDegradesToNull() throws Exception {
        long id = api.insertAnalyzed("dirty", "{\"sentiment\": \"bull");

        JsonNode item = api.getJson("/api/news/" + id);
        assertThat(item.get("analysis").isNull())
                .as("解析不了就当没有，别让一条坏数据把整屏列表带成 500")
                .isTrue();
        assertThat(item.get("analysisStatus").asText()).isEqualTo("done");
    }

    @Test
    @DisplayName("正常 JSON 原样透给前端")
    void validJsonPassesThrough() throws Exception {
        long id = api.insertAnalyzed("ok",
                "{\"sentiment\":\"bullish\",\"confidence\":0.82,\"summary\":\"利好\","
                        + "\"sectors\":[\"光伏\"],\"stocks\":[{\"name\":\"隆基\",\"code\":\"601012\"}]}");

        JsonNode a = api.getJson("/api/news/" + id).get("analysis");
        assertThat(a.get("sentiment").asText()).isEqualTo("bullish");
        assertThat(a.get("confidence").asDouble()).isEqualTo(0.82);
        assertThat(a.get("stocks").get(0).get("code").asText()).isEqualTo("601012");
    }

    @Test
    @DisplayName("少字段不算坏数据：模型只给了摘要也照常渲染")
    void missingFieldsAreTolerated() throws Exception {
        long id = api.insertAnalyzed("partial", "{\"summary\":\"只有一句话\"}");

        JsonNode a = api.getJson("/api/news/" + id).get("analysis");
        assertThat(a.get("summary").asText()).isEqualTo("只有一句话");
        assertThat(a.has("sentiment")).isFalse();
    }

    @Test
    @DisplayName("空串与 null 都不当坏数据")
    void blankAnalysisIsFine() throws Exception {
        long blank = api.insertAnalyzed("blank", "");
        long nul = api.insertAnalyzed("null", null);

        assertThat(api.getJson("/api/news/" + blank).get("analysis").isNull()).isTrue();
        assertThat(api.getJson("/api/news/" + nul).get("analysis").isNull()).isTrue();
    }

    @Test
    @DisplayName("/api/analysis/state 也吃得下脏 JSON：同步接口不能整条 500")
    void stateEndpointToleratesDirtyJson() throws Exception {
        long good = api.insertAnalyzed("good", "{\"sentiment\":\"bearish\"}");
        long bad = api.insertAnalyzed("bad", "[1,2,");

        JsonNode body = api.getJson("/api/analysis/state", "ids", good + "," + bad + ",99999");
        JsonNode items = body.get("items");

        assertThat(items.has(String.valueOf(good))).isTrue();
        assertThat(items.get(String.valueOf(good)).get("analysis").get("sentiment").asText())
                .isEqualTo("bearish");
        assertThat(items.get(String.valueOf(bad)).get("analysis").isNull()).isTrue();
        assertThat(items.has("99999")).as("不存在的 id 直接不返回，不要回一个假的空对象").isFalse();
    }

    @Test
    @DisplayName("公开口径（fulltext=false）下正文截断成摘要")
    void contentIsTruncatedWhenNotFulltext() throws Exception {
        String longText = "财联社电报正文".repeat(30);  // 210 字
        long id = api.insert("long", "标题", longText, 100);
        api.props.getDisplay().setFulltext(false);

        JsonNode item = api.getJson("/api/news/" + id);
        String text = item.get("text").asText();
        assertThat(text).endsWith("…");
        assertThat(text.length()).as("60 字 + 省略号").isEqualTo(61);
    }
}
