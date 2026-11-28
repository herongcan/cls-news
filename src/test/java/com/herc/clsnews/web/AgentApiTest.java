package com.herc.clsnews.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.agent.AgentCatalog;
import com.herc.clsnews.agent.AgentLlm;
import com.herc.clsnews.agent.AgentRunStore;
import com.herc.clsnews.agent.AgentService;
import com.herc.clsnews.agent.Retrieval;
import com.herc.clsnews.agent.SingleFlight;
import com.herc.clsnews.agent.Strategy;
import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 主线四个接口的契约测试，全部假模型、0 token。
 *
 * <p>要钉死的不是"能不能出结果"（那是 {@code AgentServiceTest} 的事），而是
 * <b>接口层的三个承诺</b>：
 * <ol>
 *   <li>{@code precheck} 一个字节都不落库 —— 它是"打开浮层不花钱"的全部依据</li>
 *   <li>{@code precheck} 与 {@code ask} 对同一批数据给出同一个判定，否则按钮上的报价是假的</li>
 *   <li>{@code runs/{id}} 拿回来的结论与 {@code ask} 当场返回的一致，重连才不会出现两版</li>
 * </ol>
 */
class AgentApiTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private FakeLlm llm;
    private AgentService service;
    private MockMvc mvc;
    private AppProps props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 跨用例递增，避免 news.item_id 唯一约束撞车 */
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.create(tmp);
        props = new AppProps();
        props.getAgent().setEnabled(true);
        props.getAgent().setDailyCap(100);
        llm = new FakeLlm();
        service = new AgentService(new AgentCatalog(), new Retrieval(jdbc), new AgentRunStore(jdbc),
                llm, new SingleFlight(), props, mapper);
        mvc = MockMvcBuilders
                .standaloneSetup(new AgentController(service, new AgentCatalog(), props,
                        new AgentStreams()))
                .build();
    }

    /**
     * 造 n 条今天的新闻，返回 id。
     *
     * <p>时刻取「今天 00:00 + 序号」，不用 now-1h：凌晨跑测试会落到昨天，
     * 候选集变成 0 条，用例以 no-data 红掉，而代码一点问题没有。
     */
    private List<Long> seed(int n) {
        long start = LocalDate.now(ZONE).atStartOfDay(ZONE).toEpochSecond() + 60;
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            seq++;
            ids.add(TestDb.insert(jdbc, "item-" + seq,
                    "第" + seq + "家公司拟回购2亿元股份用于注销",
                    "公司公告称，本次回购股份将全部用于注销并减少注册资本，回购价格不超过每股20元，"
                            + "资金来源为自有资金，实施期限为自董事会审议通过之日起十二个月内。",
                    start + seq));
        }
        return ids;
    }

    private void answer(List<Long> ids) {
        StringBuilder sb = new StringBuilder("{\"title\":\"今日主线\",\"bullets\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"text\":\"第").append(i + 1).append("个驱动因素\",\"refs\":[")
                    .append(ids.get(i)).append("]}");
        }
        llm.content = sb.append("],\"insufficient\":false}").toString();
    }

    private record Resp(int status, JsonNode body) {
    }

    private Resp getJson(String path, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(path);
        for (int i = 0; i + 1 < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return read(b);
    }

    private Resp ask(String templateId) throws Exception {
        String json = templateId == null
                ? "{}"
                : "{\"templateId\":\"" + templateId + "\"}";
        return read(post("/api/agent/ask").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Resp read(MockHttpServletRequestBuilder b) throws Exception {
        MvcResult r = mvc.perform(b).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Resp(r.getResponse().getStatus(),
                body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body));
    }

    private int runRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM agent_run", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("模板列表：只下发界面要用的字段，不含提示词正文")
    void templates() throws Exception {
        Resp r = getJson("/api/agent/templates");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("items")).hasSize(3);
        JsonNode first = r.body().get("items").get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("title")).isTrue();
        assertThat(first.has("cacheable")).isTrue();
        assertThat(first.has("system"))
                .as("提示词正文几百字一条，前端一个字都用不到")
                .isFalse();
        assertThat(first.has("costHint")).isTrue();
    }

    @Test
    @DisplayName("precheck 首次：判 full、报价大于 0、remaining 是剩下的次数、一行都不写库")
    void precheckFirstTimeIsFree() throws Exception {
        seed(8);

        Resp r = getJson("/api/agent/precheck", "templateId", "hot-today");

        assertThat(r.status()).isEqualTo(200);
        JsonNode b = r.body();
        assertThat(b.get("strategy").asText()).isEqualTo("full");
        assertThat(b.get("newCount").asInt()).as("今天还没有 base，没有「新增」可言").isZero();
        assertThat(b.get("estimateTokens").asInt()).isPositive();
        assertThat(b.get("baselineTokens").asInt())
                .as("走全量时报价就是全量价，两个数必须相等")
                .isEqualTo(b.get("estimateTokens").asInt());
        assertThat(b.get("remaining").asInt()).isEqualTo(100);
        assertThat(b.get("runId").isNull()).isTrue();
        assertThat(b.get("baseRunId").isNull()).isTrue();
        assertThat(b.get("lastRunAt").asLong())
                .as("没有已有结论时时间戳给 0，前端据此决定要不要显示 meta 行")
                .isZero();
        assertThat(runRows()).as("报价的承诺是零成本，包括不写 run").isZero();
    }

    @Test
    @DisplayName("ask 之后 precheck 判 cached，直接给出 runId，报价为 0")
    void precheckAfterAskIsCached() throws Exception {
        List<Long> ids = seed(8);
        answer(ids);

        Resp first = ask("hot-today");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("status").asText()).isEqualTo("succeeded");
        String runId = first.body().get("runId").asText();

        Resp r = getJson("/api/agent/precheck", "templateId", "hot-today");

        JsonNode b = r.body();
        assertThat(b.get("strategy").asText())
                .as("数据一个字没变，第二次必须判缓存 —— 否则报价里那个'0'就是假的")
                .isEqualTo("cached");
        assertThat(b.get("runId").asText()).isEqualTo(runId);
        assertThat(b.get("estimateTokens").asInt()).isZero();
        assertThat(b.get("note").asText()).isEqualTo(Strategy.NOTE_SAME);
        assertThat(b.get("lastRunAt").asLong()).isPositive();
    }

    @Test
    @DisplayName("第二次 ask 命中缓存：同样 runId、cached=true、note 与 noteKind 都在")
    void secondAskHitsCache() throws Exception {
        List<Long> ids = seed(8);
        answer(ids);

        Resp first = ask("hot-today");
        int calls = llm.calls;
        Resp second = ask("hot-today");

        assertThat(llm.calls).as("缓存命中不该再调模型").isEqualTo(calls);
        assertThat(second.body().get("runId").asText()).isEqualTo(first.body().get("runId").asText());
        assertThat(second.body().get("meta").get("cached").asBoolean()).isTrue();
        assertThat(second.body().get("note").asText()).isEqualTo(Strategy.NOTE_SAME);
        assertThat(second.body().get("noteKind").asText())
                .as("数据没变不涉及内容新旧，语气是普通提示")
                .isEqualTo("info");
    }

    @Test
    @DisplayName("runs/{id} 拿回来的结论与 ask 当场返回的一致")
    void snapshotMatchesAsk() throws Exception {
        List<Long> ids = seed(8);
        answer(ids);
        Resp asked = ask("hot-today");

        Resp r = getJson("/api/agent/runs/" + asked.body().get("runId").asText());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("bullets")).hasSameSizeAs(asked.body().get("bullets"));
        assertThat(r.body().get("title").asText()).isEqualTo(asked.body().get("title").asText());
        assertThat(r.body().get("meta").get("cached").asBoolean())
                .as("读的是已经付过费的结论，不该显示成刚刚生成")
                .isTrue();
        assertThat(r.body().get("meta").get("tokensIn").asInt())
                .isEqualTo(asked.body().get("meta").get("tokensIn").asInt());
    }

    @Test
    @DisplayName("不存在的 runId 给 404 + 机器码，不给空对象")
    void snapshotMissing() throws Exception {
        Resp r = getJson("/api/agent/runs/r_nope");

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body().get("code").asText()).isEqualTo("NO_RUN");
    }

    @Test
    @DisplayName("材料不足：precheck 先判 rejected（0 成本），ask 一次都不调模型")
    void rejectedByGuard() throws Exception {
        seed(2);  // hot-today 的 min-hits 是 5

        Resp pre = getJson("/api/agent/precheck", "templateId", "hot-today");
        assertThat(pre.body().get("strategy").asText())
                .as("材料不够要提前说，别让用户点下去才知道")
                .isEqualTo("rejected");
        assertThat(pre.body().get("detail").asText()).isNotBlank();
        assertThat(pre.body().get("estimateTokens").asInt()).isZero();

        Resp r = ask("hot-today");
        assertThat(r.body().get("status").asText()).isEqualTo("rejected");
        assertThat(llm.calls).isZero();
        assertThat(r.body().get("meta").get("tokensIn").asInt()).isZero();
    }

    @Test
    @DisplayName("未知模板给 400，并列出可用 id")
    void unknownTemplate() throws Exception {
        seed(8);

        Resp r = ask("no-such-template");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("NO_TEMPLATE");
        assertThat(r.body().get("error").asText()).contains("hot-today");
    }

    @Test
    @DisplayName("模型没就绪：precheck 照样给结论和报价（ready=false），ask 才 503")
    void precheckSurvivesMissingKey() throws Exception {
        List<Long> ids = seed(8);
        answer(ids);
        ask("hot-today");
        llm.ready = false;

        Resp pre = getJson("/api/agent/precheck", "templateId", "hot-today");
        assertThat(pre.status())
                .as("缓存命中不调模型，key 没了也不该影响读旧结论")
                .isEqualTo(200);
        assertThat(pre.body().get("strategy").asText()).isEqualTo("cached");
        assertThat(pre.body().get("ready").asBoolean()).isFalse();

        // 换一个没有缓存的模板：这次真的要花钱，必须先把话说清楚 —— 但仍然 200，
        // 报出 ready=false 让按钮置灰，而不是让整个浮层变成一条错误
        Resp ask = ask("nvda-bullish");
        assertThat(ask.status()).isEqualTo(503);
        assertThat(ask.body().get("code").asText()).isEqualTo("NOT_READY");

        Resp fresh = getJson("/api/agent/precheck", "templateId", "nvda-bullish");
        assertThat(fresh.status()).as("别把'不能生成'当成'不能看'").isEqualTo(200);
        assertThat(fresh.body().get("ready").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("额度过期：ask 给 429 + CAPPED，precheck 仍然 200 且 remaining=0")
    void cappedQuota() throws Exception {
        props.getAgent().setDailyCap(0);
        seed(8);

        Resp pre = getJson("/api/agent/precheck", "templateId", "hot-today");
        assertThat(pre.status()).as("报价不是花钱动作，额度用尽也要能看").isEqualTo(200);
        assertThat(pre.body().get("remaining").asInt()).isZero();

        Resp r = ask("hot-today");
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.body().get("code").asText()).isEqualTo("CAPPED");
        assertThat(llm.calls).isZero();
        assertThat(runRows()).as("被额度拦下的请求不该留下任何 run 行").isZero();
    }

    @Test
    @DisplayName("请求体缺 templateId 时给 400 而不是 500")
    void missingTemplateId() throws Exception {
        Resp r = ask(null);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("NO_TEMPLATE");
    }

    /** 造一条指定创建时间的 running 行。体检的僵尸判定全靠时间戳，所以要能控制它。 */
    private void seedRunning(long createdAt) {
        jdbc.update("""
                INSERT INTO agent_run (id, kind, template_id, params_json, trigger, trigger_by,
                                       window_key, strategy, watermark, status, created_at)
                VALUES (?, 'template', 'hot-today', '{}', 'user', 'tester',
                        ?, 'full', 0, 'running', ?)
                """, "r_stuck_" + createdAt, LocalDate.now(ZONE).toString(), createdAt);
    }

    @Test
    @DisplayName("health：模板/模型/今日用量/剩余额度都对得上，且调用它不产生任何 run 行")
    void healthReportsBasics() throws Exception {
        List<Long> ids = seed(8);
        answer(ids);
        ask("hot-today");
        int before = runRows();

        Resp r = getJson("/api/agent/health");

        assertThat(r.status()).isEqualTo(200);
        JsonNode b = r.body();
        assertThat(b.get("status").asText()).isEqualTo("UP");
        assertThat(b.get("enabled").asBoolean()).isTrue();
        assertThat(b.get("templates").get("count").asInt()).isEqualTo(3);
        assertThat(b.get("templates").get("ids")).hasSize(3);
        assertThat(b.get("templates").get("error").isNull())
                .as("加载失败的原因是 null 之外的唯一信号，正常时必须为空")
                .isTrue();
        assertThat(b.get("llm").get("ready").asBoolean()).isTrue();
        assertThat(b.get("llm").get("model").asText()).isEqualTo("fake-model");
        assertThat(b.get("today").get("succeeded").asLong()).isEqualTo(1);
        assertThat(b.get("today").get("tokensIn").asLong())
                .as("花了多少 token 必须能在这里对账，否则只能去翻库")
                .isEqualTo(8_600);
        assertThat(b.get("quota").get("used").asLong())
                .as("体检的 used 与 checkQuota 数的是同一批行，两边口径必须一致")
                .isEqualTo(1);
        assertThat(b.get("quota").get("remaining").asLong()).isEqualTo(99);
        assertThat(b.get("problems")).hasSize(0);
        assertThat(runRows()).as("体检是只读的，读一次不该让账上多出一行").isEqualTo(before);
    }

    @Test
    @DisplayName("key 缺失：health 仍给 200，但状态 DEGRADED 并说清原因")
    void healthDegradesWithoutKey() throws Exception {
        llm.ready = false;

        Resp r = getJson("/api/agent/health");

        assertThat(r.status())
                .as("降级不配 503：接上探针会让容器为一个过期的 key 反复重启，而重启治不好 key")
                .isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("DEGRADED");
        assertThat(r.body().get("llm").get("ready").asBoolean()).isFalse();
        assertThat(r.body().get("problems").toString()).contains("模型未就绪");
    }

    @Test
    @DisplayName("开关关闭时也报 DEGRADED；响应体里不含任何 key 字段")
    void healthHonoursDisabledAndLeaksNoKey() throws Exception {
        props.getAgent().setEnabled(false);

        Resp r = getJson("/api/agent/health");

        assertThat(r.body().get("status").asText()).isEqualTo("DEGRADED");
        assertThat(r.body().get("enabled").asBoolean()).isFalse();
        assertThat(r.body().get("problems").toString()).contains("已关闭");
        assertThat(r.body().toString())
                .as("体检是诊断，不是配置转储：把 key 打出来只会出现在日志和截图里")
                .doesNotContain("apiKey")
                .doesNotContain("api-key")
                .doesNotContain("baseUrl");
    }

    @Test
    @DisplayName("刚好在跑的 running 不算僵尸：count>0 但 stuck=0")
    void healthDoesNotFlagFreshRunning() throws Exception {
        seedRunning(System.currentTimeMillis() / 1000);

        Resp r = getJson("/api/agent/health");

        JsonNode run = r.body().get("running");
        assertThat(run.get("count").asLong()).isEqualTo(1);
        assertThat(run.get("stuck").asLong())
                .as("generating 期间打体检是常事，这时候报僵尸会让人白紧张")
                .isZero();
    }

    @Test
    @DisplayName("超过 stale-seconds 的 running 算僵尸，并报出挂了多久")
    void healthFlagsStuck() throws Exception {
        int stale = props.getAgent().getStaleSeconds();
        seedRunning(System.currentTimeMillis() / 1000 - stale - 60);

        Resp r = getJson("/api/agent/health");

        JsonNode run = r.body().get("running");
        assertThat(run.get("count").asLong()).isEqualTo(1);
        assertThat(run.get("oldestAgeSec").asLong())
                .as("只报个数不够 —— 挂了 3 分钟和挂了 3 小时的处理方式不一样")
                .isGreaterThan((long) stale);
        assertThat(run.get("stuck").asLong()).isEqualTo(1);
        assertThat(run.get("staleSeconds").asInt()).isEqualTo(stale);
    }

    /** 假模型：能记账、能模拟"key 没配"。 */
    static final class FakeLlm implements AgentLlm {

        private volatile int calls;
        private String content = "{}";
        private boolean ready = true;

        @Override
        public synchronized Reply chat(String system, String user) {
            calls++;
            return new Reply(content, 8_600, 380, "fake-model", 120);
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public String modelName() {
            return "fake-model";
        }
    }
}
