package com.herc.clsnews.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.ai.AnalysisResult;
import com.herc.clsnews.ai.AnalysisService;
import com.herc.clsnews.ai.Analyzer;
import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.job.IngestJob;
import com.herc.clsnews.model.News;
import com.herc.clsnews.source.NewsSource;
import com.herc.clsnews.store.AccessLogStore;
import com.herc.clsnews.store.NewsStore;
import com.herc.clsnews.store.VisitStore;
import com.herc.clsnews.web.NewsController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 接口层的测试台：真实 store + 真实 Controller，只把「会联网的那两个」换成假对象。
 *
 * <p>为什么不用 {@code @SpringBootTest}：起全容器会真的跑 {@code @Scheduled} 的采集与
 * 分析任务，测试得塞假 key、还会因为时间到了而开始花钱；失败原因也会被一堆 Bean 初始化日志埋掉。
 * 这里要断言的是「接口返回的 JSON 长什么样」，一个 standalone MockMvc 足够，也快。
 */
public final class ApiHarness {

    public final JdbcTemplate jdbc;
    public final NewsStore store;
    public final AppProps props;
    public final ObjectMapper mapper = new ObjectMapper();
    public final MockMvc mvc;

    private ApiHarness(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.store = new NewsStore(jdbc);
        this.props = new AppProps();

        // 采集任务与数据源只用于 /api/status 的几个展示字段，不需要真实现
        IngestJob ingest = mock(IngestJob.class);
        NewsSource source = mock(NewsSource.class);
        when(source.name()).thenReturn("test");

        AnalysisService analysis = new AnalysisService(store, new StubAnalyzer(), props, mapper);
        NewsController controller = new NewsController(store, new VisitStore(jdbc),
                new AccessLogStore(jdbc), ingest, source, analysis, props, mapper);
        this.mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    public static ApiHarness create(Path dir) throws Exception {
        return new ApiHarness(TestDb.create(dir));
    }

    /** 插一条新闻，返回 id。 */
    public long insert(String itemId, String title, String content, long publishTime) {
        return TestDb.insert(jdbc, itemId, title, content, publishTime);
    }

    public long insert(String itemId, long publishTime) {
        return TestDb.insert(jdbc, itemId, publishTime);
    }

    /** 插一条「已分析」的新闻，带上任意 analysis_json（可以是脏数据）。 */
    public long insertAnalyzed(String itemId, String analysisJson) {
        long id = TestDb.insert(jdbc, itemId, 100);
        jdbc.update("UPDATE news SET analysis_status='done', analysis_json=?, analyzed_at=? WHERE id=?",
                analysisJson, System.currentTimeMillis() / 1000, id);
        return id;
    }

    public JsonNode getJson(String path, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(path);
        for (int i = 0; i + 1 < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        String body = mvc.perform(b).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(body);
    }

    private static final class StubAnalyzer implements Analyzer {
        @Override
        public AnalysisResult analyze(News news) {
            throw new UnsupportedOperationException("接口用例不该真的调模型");
        }

        @Override
        public String modelName() {
            return "stub";
        }
    }
}
