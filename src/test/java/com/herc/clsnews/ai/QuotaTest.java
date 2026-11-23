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
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日配额。两层上限（全站 daily-cap + 游客独立额度）必须都判：
 * 只判角色额度，游客能突破全站上限把钱烧光；只判全站，一个游客就能吃掉所有人的份额。
 *
 * <p>按「调用次数」而不是「分析过的行数」计：同一行可以被重算多次，
 * 按行数计永远撞不到上限，而钱是一次一次花的。
 */
class QuotaTest {

    @TempDir
    Path dir;

    private JdbcTemplate jdbc;
    private NewsStore store;
    private AppProps props;
    private AnalysisService service;
    private long newsId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.create(dir);
        store = new NewsStore(jdbc);
        newsId = TestDb.insert(jdbc, "a", 100);

        props = new AppProps();
        props.getSecurity().setGuestDailyCap(10);
        props.getAnalysis().setDailyCap(300);
        // 构造会顺便清 7 天前的调用记录，所以先建 service 再往表里塞历史行
        service = new AnalysisService(store, new StubAnalyzer(), props, new ObjectMapper());
    }

    private void calls(int n, String actor) {
        for (int i = 0; i < n; i++) {
            store.recordCall(newsId, actor);
        }
    }

    @Test
    @DisplayName("游客额度：第 10 次用完封顶，主账号不受影响")
    void guestCapIsPerRole() {
        calls(9, "guest");
        assertThat(service.capped("guest", 10)).as("9 次时还能点").isFalse();

        calls(1, "guest");
        assertThat(service.capped("guest", 10)).as("第 10 次之后就封顶").isTrue();
        assertThat(service.capped("rw", null))
                .as("游客的额度不该把主账号一起封了 —— 那是两笔预算")
                .isFalse();
    }

    @Test
    @DisplayName("全站上限对所有人生效，包括没有独立额度的主账号")
    void globalCapAppliesToEveryone() {
        props.getAnalysis().setDailyCap(3);
        calls(3, "guest");

        assertThat(service.capped("rw", null)).as("游客花掉的也是真钱，全站上限要算进来").isTrue();
        assertThat(service.capped("guest", 10)).isTrue();
    }

    @Test
    @DisplayName("计数按 actor 分开：guest 用的不算到 rw 头上")
    void actorCountsAreSeparate() {
        calls(3, "guest");
        calls(1, "rw");

        assertThat(service.usedToday("guest")).isEqualTo(3);
        assertThat(service.usedToday("rw")).isEqualTo(1);
        assertThat(service.usedToday()).as("无 actor = 全站总数").isEqualTo(4);
    }

    @Test
    @DisplayName("昨天（跨自然日）的调用不计入今天")
    void yesterdayDoesNotCount() {
        long startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        jdbc.update("INSERT INTO analysis_call (ts, news_id, actor) VALUES (?,?,?)",
                startOfToday - 60, newsId, "guest");

        assertThat(service.usedToday()).isZero();
        assertThat(service.capped("guest", 10)).isFalse();
    }

    @Test
    @DisplayName("清理只删 7 天前的记录，近期的不动")
    void pruneOnlyRemovesOldCalls() {
        long now = System.currentTimeMillis() / 1000;
        long old = now - 10L * 24 * 3600;
        jdbc.update("INSERT INTO analysis_call (ts, news_id, actor) VALUES (?,?,?)", old, newsId, "guest");
        calls(1, "guest");

        assertThat(store.pruneCalls(now - 7L * 24 * 3600)).isEqualTo(1);
        assertThat(service.usedToday("guest")).isEqualTo(1);
    }

    /** 只用来满足构造参数：这些用例只关心计数，不会真的调模型。 */
    private static final class StubAnalyzer implements Analyzer {
        @Override
        public AnalysisResult analyze(News news) {
            throw new UnsupportedOperationException("配额用例不该真的调模型");
        }

        @Override
        public String modelName() {
            return "stub";
        }
    }
}
