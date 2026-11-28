package com.herc.clsnews.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「过程」的文案与结构约束。
 *
 * <p>这些东西没法用「看起来对不对」来验：浮层只有 390px 宽，一行放不下就折行，
 * 折了行整块区域会顶变形（S1.4 的 note 原型踩过）。所以把宽度写成断言，
 * 改超了直接红，而不是等真机上看着别扭才发现。
 */
class StepsTest {

    private static PackResult pack(int fetched, int promo, int merged,
                                   int l1n, int l2n, int demoted, int dropped) {
        List<PackedNews> l1 = new ArrayList<>();
        for (int i = 0; i < l1n; i++) {
            l1.add(new PackedNews(100 + i, "标题" + i, 0, "正文", null, 10, true));
        }
        List<PackedNews> l2 = new ArrayList<>();
        for (int i = 0; i < l2n; i++) {
            l2.add(new PackedNews(200 + i, "标题" + i, 0, null, null, 1, false));
        }
        return new PackResult(l1, l2, fetched, promo, Map.of(), merged, 3500, 30000, demoted, dropped);
    }

    @Test
    @DisplayName("没有剔除和合并时不生成 0 值行")
    void skipsZeroRows() {
        List<Step> steps = Steps.fromScan(pack(69, 0, 0, 48, 21, 0, 0));

        assertThat(steps).extracting(Step::k).containsExactly("scan", "pack");
        assertThat(steps.get(0).value()).isEqualTo("69 条");
    }

    @Test
    @DisplayName("剔除与合并各占一行，数字来自打包器")
    void showsFilterAndMerge() {
        List<Step> steps = Steps.fromScan(pack(69, 12, 9, 48, 0, 0, 0));

        assertThat(steps).extracting(Step::k)
                .containsExactly("scan", "filter", "merge", "pack");
        assertThat(steps.get(1).value()).isEqualTo("12 条");
        assertThat(steps.get(2).value()).isEqualTo("9 条");
    }

    @Test
    @DisplayName("超预算时说明降级与丢弃，正常时说明分层")
    void packHintExplainsBudget() {
        Step trimmed = Steps.fromScan(pack(600, 0, 0, 80, 500, 10, 3)).get(1);
        assertThat(trimmed.hint()).contains("降级 10").contains("丢弃 3");

        Step normal = Steps.fromScan(pack(69, 0, 0, 48, 21, 0, 0)).get(1);
        assertThat(normal.hint()).contains("含正文 48").contains("仅标题 21");
    }

    @Test
    @DisplayName("拒答把原因写进 hint —— 那正是用户要的答案")
    void rejectionCarriesDetail() {
        Step s = Steps.guard(Guard.Verdict.reject("min-hits", "候选只有 1 条，不足 3 条"));

        assertThat(s.value()).isEqualTo("不足");
        assertThat(s.hint()).isEqualTo("候选只有 1 条，不足 3 条");
    }

    @Test
    @DisplayName("通过时不留 hint，避免一行两个「通过」")
    void passHasNoHint() {
        Step s = Steps.guard(new Guard.Verdict(false, null, null));

        assertThat(s.value()).isEqualTo("通过");
        assertThat(s.hint()).isNull();
    }

    @Test
    @DisplayName("引用全部有效时说「全部有效」，不写「剔掉 0 个」")
    void citeWithoutDrops() {
        AnswerParser.Result clean = new AnswerParser.Result(
                new Answer("t", List.of(), false), 0, 0, null);

        assertThat(Steps.cite(clean).value()).isEqualTo("全部有效");
    }

    @Test
    @DisplayName("引用被剔掉时给出个数")
    void citeWithDrops() {
        AnswerParser.Result dirty = new AnswerParser.Result(
                new Answer("t", List.of(), false), 0, 2, null);

        Step s = Steps.cite(dirty);
        assertThat(s.value()).isEqualTo("剔掉 2 个");
        assertThat(s.hint()).isEqualTo("不在候选集内");
    }

    @Test
    @DisplayName("耗时保留一位小数，token 放 hint")
    void llmDuration() {
        Step s = Steps.llm(6234, 3770);

        assertThat(s.value()).isEqualTo("6.2 秒");
        assertThat(s.hint()).isEqualTo("3770 token");
    }

    @Test
    @DisplayName("所有标签与主值都在浮层一行之内")
    void copyFitsOneLine() {
        List<Step> all = new ArrayList<>(Steps.fromScan(pack(600, 12, 9, 80, 500, 10, 3)));
        all.add(Steps.guard(Guard.Verdict.reject("min-hits", "候选只有 1 条，不足 3 条")));
        all.add(Step.of("delta", "增量基线", "新增 12 条"));
        all.add(Steps.llm(6234, 3770));
        all.add(Steps.cite(new AnswerParser.Result(new Answer("t", List.of(), false), 0, 2, null)));

        for (Step s : all) {
            assertThat(s.label().length())
                    .as("label 超宽：%s", s.label())
                    .isLessThanOrEqualTo(Steps.MAX_LABEL);
            assertThat(s.value().length())
                    .as("value 超宽：%s", s.value())
                    .isLessThanOrEqualTo(Steps.MAX_VALUE);
        }
    }

    @Test
    @DisplayName("存进 steps_json 再读回来，字段一个不少")
    void jsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Step> origin = Steps.fromScan(pack(69, 12, 9, 48, 21, 0, 0));
        origin.add(Steps.guard(new Guard.Verdict(false, null, null)));
        origin.add(Steps.llm(6234, 3770));

        String json = mapper.writeValueAsString(origin);
        List<Step> back = mapper.readValue(json, new TypeReference<List<Step>>() {
        });

        assertThat(back).isEqualTo(origin);
    }
}
