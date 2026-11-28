package com.herc.clsnews.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.model.News;
import com.herc.clsnews.store.NewsStore;
import com.herc.clsnews.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 栏目导流条目的前置拦截。
 *
 * <p>为什么值得单独立测：这条规则唯一的产出是"省下不该花的钱"，而省钱这件事
 * 在正常路径上<strong>看不出来</strong> —— 拦截生效和拦截失效，接口都不会报错，
 * 区别只在账单上。所以必须靠断言"模型调用次数为 0、记账次数不变"来证明。
 *
 * <p>三个必须同时成立的边界：
 * ① 导流条被拦在模型调用之前；② 历史上已分析过的导流条仍能取到缓存（不能因为新规则把旧结果藏掉）；
 * ③ 正常条目和「【」不在标题首位的条目不能被误杀。
 */
class PromoSkipTest {

    @TempDir
    Path dir;

    private JdbcTemplate jdbc;
    private NewsStore store;
    private AppProps props;
    private CountingAnalyzer analyzer;
    private AnalysisService service;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.create(dir);
        store = new NewsStore(jdbc);

        props = new AppProps();
        props.getAnalysis().setEnabled(true);
        props.getAnalysis().setDailyCap(300);

        analyzer = new CountingAnalyzer();
        service = new AnalysisService(store, analyzer, props, new ObjectMapper());
    }

    @Test
    @DisplayName("导流条被拦在调模型之前：0 次调用、0 次记账")
    void promoIsRejectedBeforeCallingModel() {
        long id = TestDb.insert(jdbc, "promo-1",
                "【风口研报】这家公司布局了液冷，机构密集调研", "正文是荐股话术", 100);
        long usedBefore = service.usedToday();

        assertThatThrownBy(() -> service.analyzeNews(id, false, "rw", null))
                .isInstanceOf(AnalysisException.class)
                .satisfies(e -> {
                    AnalysisException ae = (AnalysisException) e;
                    assertThat(ae.status()).as("400 而不是 5xx：这不是故障，是不该发这个请求").isEqualTo(400);
                    assertThat(ae.code()).isEqualTo("PROMO_ITEM");
                });

        assertThat(analyzer.calls.get()).as("一次模型都不该调，否则省钱无从谈起").isZero();
        assertThat(service.usedToday()).as("被拦的这条不扣次数").isEqualTo(usedBefore);
    }

    @Test
    @DisplayName("历史上已分析过的导流条仍返回缓存，不因为新规则报错")
    void analyzedPromoStillServesCache() {
        long id = TestDb.insert(jdbc, "promo-2", "【盘中宝】5 只标的同时异动", "…", 100);
        // 造一条"刚才分析完"的记录：状态 done + analyzed_at 在当前 90 秒窗口内
        store.saveAnalysis(id, "{\"sentiment\":\"neutral\"}", "stub");

        AnalysisService.Outcome o = service.analyzeNews(id, false, "rw", null);

        assertThat(o.cached()).as("旧结果要能取到，否则用户会以为这条坏了").isTrue();
        assertThat(o.item().title()).isEqualTo("【盘中宝】5 只标的同时异动");
        assertThat(analyzer.calls.get()).as("缓存命中不该产生新的模型调用").isZero();
    }

    @Test
    @DisplayName("正常条目不被误杀")
    void normalItemIsNotBlocked() {
        long id = TestDb.insert(jdbc, "normal-1", "德福科技：拟1亿元至2亿元回购股份", "公告正文", 100);

        AnalysisService.Outcome o = service.analyzeNews(id, false, "rw", null);

        assertThat(o.cached()).isFalse();
        assertThat(analyzer.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("「【」不在标题首位的不拦：判据是结构前缀，不是出现过就算")
    void bracketNotAtStartIsNotPromo() {
        long id = TestDb.insert(jdbc, "normal-2", "解读【重磅】：某部委发布新规", "正文", 100);

        AnalysisService.Outcome o = service.analyzeNews(id, false, "rw", null);

        assertThat(o.cached()).isFalse();
        assertThat(analyzer.calls.get()).as("只看首位，不做 contains —— 后者会误杀大量引用式标题").isEqualTo(1);
    }

    /** 只数调用次数，返回一个结构合法的最小结果。 */
    private static final class CountingAnalyzer implements Analyzer {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public AnalysisResult analyze(News news) {
            calls.incrementAndGet();
            return new AnalysisResult("neutral", 0.5, "测试结论", "", List.of(), List.of(), "stub");
        }

        @Override
        public String modelName() {
            return "stub";
        }
    }
}
