package com.herc.clsnews.ai;

import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.model.News;
import com.herc.clsnews.store.NewsStore;
import com.herc.clsnews.support.TestDb;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 5xx 那句话是给同事看的，不是给运维看的。
 *
 * <p>缘起：2026-11-28 验收起实例时 key 没配全，页面上直接铺出一条红字
 * 「大模型未就绪，检查 DEEPSEEK_API_KEY 是否配置正确」。看到它的是十来个人共用的内网工具，
 * 里面没有一个人会去改 .env —— 他们只会来问一句"坏了？"。
 *
 * <p>所以这条约束写成测试：<b>用户可见的提示里不许出现配置项名</b>。
 * 排障信息该去的地方是启动日志和 {@code /api/agent/health} 的 problems ——
 * 那里读者是运维，写多细都行；这里读者是同事，写配置项只会制造一次无效沟通。
 *
 * <p>和 {@code NoteCopyTest} 同一类：文案是接口的一部分，靠注释约束等于没约束。
 */
class AnalysisCopyTest {

    @TempDir
    Path dir;

    private NewsStore store;
    private AppProps props;
    private long newsId;

    /** ready()=false 的模型：模拟"key 没配好"，但采集和分析之外的链路都正常。 */
    private static final Analyzer NOT_READY = new Analyzer() {
        @Override
        public AnalysisResult analyze(News news) {
            throw new AssertionError("不该走到调用模型这一步");
        }

        @Override
        public String modelName() {
            return "fake";
        }

        @Override
        public boolean ready() {
            return false;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = TestDb.create(dir);
        store = new NewsStore(jdbc);
        newsId = TestDb.insert(jdbc, "a", 100);
        props = new AppProps();
        props.getAnalysis().setEnabled(true);
    }

    private AnalysisService service(Analyzer analyzer) {
        return new AnalysisService(store, analyzer, props, new ObjectMapper());
    }

    @Test
    @DisplayName("模型没就绪：给人看的是人话，配置项名不外泄")
    void notReadySpeaksHuman() {
        assertThatThrownBy(() -> service(NOT_READY).analyzeNews(newsId, false, "rw", null))
                .isInstanceOfSatisfying(AnalysisException.class, e -> {
                    // code 留给脚本判断，文案留给人看，两者不能混用
                    assertThat(e.code()).isEqualTo("NOT_READY");
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.getMessage())
                            .doesNotContain("DEEPSEEK")
                            .doesNotContain("api-key")
                            .doesNotContain("app.")
                            .doesNotContain(".env")
                            .doesNotContain("配置");
                    assertThat(e.getMessage()).isEqualTo("分析功能暂不可用，稍后再试");
                });
    }

    @Test
    @DisplayName("功能被关掉：同样不写 app.analysis.enabled")
    void disabledSpeaksHuman() {
        props.getAnalysis().setEnabled(false);

        assertThatThrownBy(() -> service(NOT_READY).analyzeNews(newsId, false, "rw", null))
                .isInstanceOfSatisfying(AnalysisException.class, e -> {
                    assertThat(e.code()).isEqualTo("DISABLED");
                    assertThat(e.getMessage()).doesNotContain("app.").doesNotContain("enabled");
                    assertThat(e.getMessage()).isEqualTo("分析功能已暂停");
                });
    }

    @Test
    @DisplayName("额度用完：说清是哪个上限、多少条，但不写配置项名")
    void cappedWithoutConfigName() {
        props.getAnalysis().setDailyCap(0); // usedToday() >= 0 直接命中
        // 必须用 ready 的模型：NOT_READY 的判断在配额之前，用它会先撞上 NOT_READY
        Analyzer ready = new Analyzer() {
            @Override
            public AnalysisResult analyze(News news) {
                throw new AssertionError("额度这一关就该拦住，不该走到调用");
            }

            @Override
            public String modelName() {
                return "fake";
            }
        };

        assertThatThrownBy(() -> service(ready).analyzeNews(newsId, false, "rw", null))
                .isInstanceOfSatisfying(AnalysisException.class, e -> {
                    assertThat(e.code()).isEqualTo("CAPPED");
                    assertThat(e.getMessage()).contains("上限");
                    assertThat(e.getMessage()).doesNotContain("app.").doesNotContain("daily-cap");
                });
    }

    @Test
    @DisplayName("模型报错：不把上游异常原文铺到页面上")
    void modelErrorSpeaksHuman() {
        Analyzer broken = new Analyzer() {
            @Override
            public AnalysisResult analyze(News news) throws Exception {
                // 真实世界里这里长这样：Read timed out / 上游 401 的 JSON 片段
                throw new java.io.IOException("Read timed out");
            }

            @Override
            public String modelName() {
                return "fake";
            }
        };

        assertThatThrownBy(() -> service(broken).analyzeNews(newsId, false, "rw", null))
                .isInstanceOfSatisfying(AnalysisException.class, e -> {
                    assertThat(e.code()).isEqualTo("MODEL_ERROR");
                    assertThat(e.getMessage()).doesNotContain("Read timed out").doesNotContain("Exception");
                    assertThat(e.getMessage()).isEqualTo("模型返回异常，稍后再试");
                });
    }

    @Test
    @DisplayName("文案长度：要能在一行提示条里放下")
    void copyFitsOneLine() {
        // 提示条宽度按 390px 屏算约 25 个字；留出余量取 14
        assertThat("分析功能暂不可用，稍后再试".length()).isLessThanOrEqualTo(14);
        assertThat("分析功能已暂停".length()).isLessThanOrEqualTo(14);
        assertThat("模型返回异常，稍后再试".length()).isLessThanOrEqualTo(14);
    }
}
