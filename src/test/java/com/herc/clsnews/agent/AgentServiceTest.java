package com.herc.clsnews.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主线编排的分支测试，全部用假模型。
 *
 * <p>为什么必须假模型：这个类里每一条分支的代价都是"一万 token 或零 token"。
 * 真模型只能验证"大概率能出结果"，验证不了"材料不足时一次都不该调用"
 * ——而后者恰恰是最贵的 bug：调用发生了、钱花了，只是没人发现。
 *
 * <p>每个用例都在断言两件事：<b>结果对不对</b>，以及<b>该不该花钱</b>。
 * 后者的证据是 {@link FakeLlm#calls} 与 {@code agent_run.tokens_in}。
 */
class AgentServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private FakeLlm llm;
    private SingleFlight flights;
    private AgentService service;
    private AppProps props;

    /** 跨调用递增，保证多次 seed 的 item_id 不撞（news.item_id 有唯一约束）。 */
    private int seq = 0;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.create(tmp);
        props = new AppProps();
        props.getAgent().setEnabled(true);
        props.getAgent().setDailyCap(100);
        llm = new FakeLlm();
        flights = new SingleFlight();
        service = new AgentService(new AgentCatalog(), new Retrieval(jdbc), new AgentRunStore(jdbc),
                llm, flights, props, new ObjectMapper());
    }

    /**
     * 造 n 条今天的新闻，返回它们的 id。
     *
     * <p>时间用 {@code 今天 00:00 + 60 + i} 而不是 {@code now - 1h}：
     * 后者在凌晨跑测试时会落到昨天，候选集变成 0 条，测试会以"no-data"红掉，
     * 而真正的代码一点问题没有 —— 这种假红比没测试更消耗人。
     */
    private List<Long> seed(int n) {
        long start = LocalDate.now(ZONE).atStartOfDay(ZONE).toEpochSecond() + 60;
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            seq++;
            // 标题必须各不相同：完全重复的标题会被 Classifier 合并掉
            ids.add(TestDb.insert(jdbc, "item-" + seq,
                    "第" + seq + "家公司拟回购2亿元股份用于注销",
                    "公司公告称，本次回购股份将全部用于注销并减少注册资本，回购价格不超过每股20元，"
                            + "资金来源为自有资金，实施期限为自董事会审议通过之日起十二个月内。",
                    start + seq));
        }
        return ids;
    }

    private String answerWith(List<Long> refs) {
        return answerWithGroups(List.of(refs));
    }

    /** 每条 group 生成一条主线，用来造"多主线"的真实形态（平均 3–5 条）。 */
    private String answerWithGroups(List<List<Long>> groups) {
        StringBuilder sb = new StringBuilder("{\"title\":\"今日主线\",\"bullets\":[");
        int i = 0;
        for (List<Long> g : groups) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"text\":\"第").append(++i).append("个驱动因素\",\"refs\":[");
            for (int k = 0; k < g.size(); k++) {
                if (k > 0) {
                    sb.append(',');
                }
                sb.append(g.get(k));
            }
            sb.append("]}");
        }
        return sb.append("],\"insufficient\":false}").toString();
    }

    @Test
    @DisplayName("材料不足直接拒答：0 次调用、0 token、状态 rejected")
    void guardRejectsWithoutCallingModel() {
        seed(2);  // hot-today 的 min-hits 是 5

        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertEquals("rejected", r.status());
        assertEquals("min-hits", r.reason());
        assertTrue(r.detail().contains("至少需要 5 条"), r.detail());
        assertEquals(0, llm.calls, "guard 判定在调模型之前，一次都不该调");
        assertEquals(0, r.tokensIn());
        assertEquals(0, r.tokensOut());
        // 拒答也要留痕：否则用户说"点了没反应"时无法回答为什么
        assertEquals("rejected", jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id = ?", String.class, r.runId()));
    }

    @Test
    @DisplayName("生成成功时过程写进库：按 runId 读回来能解释结论怎么来的")
    void stepsExplainWhereTheAnswerCameFrom() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));

        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        List<String> keys = r.steps().stream().map(Step::k).toList();
        for (String k : List.of("scan", "pack", "guard", "llm", "cite")) {
            assertTrue(keys.contains(k), "缺少步骤 " + k + "：" + keys);
        }

        // 关键：过程落在库里，而不是只挂在这一份返回值上 ——
        // 换台设备按 runId 读、或第二天回看，都还要看得到
        AgentService.Result back = service.snapshot(r.runId()).orElseThrow();
        assertEquals(r.steps(), back.steps());
    }

    @Test
    @DisplayName("拒答也留过程：停在材料校验，hint 就是拒答原因")
    void rejectedRunKeepsSteps() {
        seed(2);  // hot-today 的 min-hits 是 5

        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        Step guard = r.steps().stream()
                .filter(s -> "guard".equals(s.k()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("拒答路径没有 guard 步骤：" + r.steps()));
        assertEquals("不足", guard.value());
        assertTrue(guard.hint().contains("至少需要 5 条"), guard.hint());
        assertEquals(r.steps(), service.snapshot(r.runId()).orElseThrow().steps());
    }

    @Test
    @DisplayName("流式：sink 按顺序收到每一步，模型那步先「等待中」再变成耗时")
    void sinkSeesStepsInOrder() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));

        List<Step> pushed = new ArrayList<>();
        AgentService.Result r = service.ask("hot-today", null, false, "admin", null, pushed::add);

        // 模型那一步是唯一会推两次的：先占位「等待中」，回来再覆盖成耗时。
        // 断言两次都推了 —— 少了第一次，界面上那十几秒就是一片空白
        long waiting = pushed.stream().filter(s -> "llm".equals(s.k()) && "等待中".equals(s.value())).count();
        assertEquals(1, waiting, "没有推「等待中」：" + pushed);
        assertEquals("llm", pushed.get(pushed.size() - 2).k());
        assertNotEquals("等待中", pushed.get(pushed.size() - 2).value());

        // 推出去的最后一份，必须和落库的那份完全一致：否则"界面看到的"和
        // "事后按 runId 查到的"是两套数字，而排障时人只会信后者
        assertEquals(r.steps(), lastByKey(pushed));
        assertEquals(r.steps(), service.snapshot(r.runId()).orElseThrow().steps());
    }

    @Test
    @DisplayName("流式：模型思考逐段推给 sink，全文落库成 reasoning 那行")
    void reasoningStreamedAndPersisted() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));
        llm.reasoning = "先扫标题找主题，再核对引用编号是否在候选集里";

        List<String> chunks = new ArrayList<>();
        List<Step> pushed = new ArrayList<>();
        Proc.Sink sink = new Proc.Sink() {
            @Override
            public void step(Step s) {
                pushed.add(s);
            }

            @Override
            public void reasoning(String chunk) {
                chunks.add(chunk);
            }
        };

        AgentService.Result r = service.ask("hot-today", null, false, "admin", null, sink);

        // 思考被切成了若干段逐段推（FakeLlm 按长度切三段），拼起来是全文
        assertFalse(chunks.isEmpty(), "模型吐了思考就该推给界面");
        assertEquals(llm.reasoning, String.join("", chunks));

        // 全文落库成 reasoning 那行，hint 是纯思考文本
        assertThat(r.steps()).anyMatch(s -> "reasoning".equals(s.k())
                && llm.reasoning.equals(s.hint()));
        // 读回旧结论时（snapshot），reasoning 那行还在 —— 思考卡片不能只在生成中可见
        assertThat(service.snapshot(r.runId()).orElseThrow().steps())
                .anyMatch(s -> "reasoning".equals(s.k()));
    }

    @Test
    @DisplayName("模型不吐思考时不产生 reasoning 行，也不推空段")
    void noReasoningProducesNoStep() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));
        llm.reasoning = null;

        List<String> chunks = new ArrayList<>();
        Proc.Sink sink = new Proc.Sink() {
            @Override
            public void step(Step s) {
            }

            @Override
            public void reasoning(String chunk) {
                chunks.add(chunk);
            }
        };

        AgentService.Result r = service.ask("hot-today", null, false, "admin", null, sink);

        assertTrue(chunks.isEmpty());
        assertThat(r.steps()).noneMatch(s -> "reasoning".equals(s.k()));
    }

    @Test
    @DisplayName("流式：客户端断开（sink 抛异常）不能把这次生成带崩")
    void sinkFailureDoesNotBreakGeneration() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));

        AgentService.Result r = service.ask("hot-today", null, false, "admin", null, s -> {
            throw new IllegalStateException("客户端已断开");
        });

        // 断开发生在取数之后、模型之前。钱一旦花出去，结论就必须落库 ——
        // 若让它连着失败，用户付了费却什么都没留下，下次还得再付一次
        assertEquals("succeeded", r.status());
        assertEquals(1, llm.calls);
        assertEquals("succeeded", service.snapshot(r.runId()).orElseThrow().status());
        assertFalse(r.steps().isEmpty());
    }

    @Test
    @DisplayName("流式：拒答也要推到最后一行，不能让界面停在「材料校验」之前")
    void rejectedPushesGuardStep() {
        seed(2);  // min-hits = 5

        List<Step> pushed = new ArrayList<>();
        service.ask("hot-today", null, false, "admin", null, pushed::add);

        Step last = pushed.get(pushed.size() - 1);
        assertEquals("guard", last.k());
        assertEquals("不足", last.value());
        assertTrue(last.hint().contains("至少需要 5 条"), last.hint());
    }

    /** 按 k 归并：同 k 取最后一次（和 {@link Proc#replace} 与前端渲染同一套语义）。 */
    private static List<Step> lastByKey(List<Step> pushed) {
        java.util.Map<String, Step> m = new java.util.LinkedHashMap<>();
        for (Step s : pushed) {
            m.put(s.k(), s);
        }
        return new ArrayList<>(m.values());
    }

    @Test
    @DisplayName("缓存命中时过程读的是上次那份，不是这次重新算的")
    void cachedRunCarriesOriginalSteps() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));

        AgentService.Result first = service.ask("hot-today", null, "admin", null);
        AgentService.Result second = service.ask("hot-today", null, "admin", null);

        assertTrue(second.cached(), "第二次应命中缓存");
        assertEquals(first.steps(), second.steps());
    }

    @Test
    @DisplayName("关键词大小写与多余空格不产生新的缓存键：同一个词只花一次钱")
    void keywordKeyIgnoresCaseAndSpaces() {
        assertEquals("ai", AgentService.normalizeKeyword("  AI  "));
        assertEquals("a b", AgentService.normalizeKeyword(" a   b "));

        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));

        AgentService.Result first = service.ask("hot-today", "回购", "admin", null);
        AgentService.Result second = service.ask("hot-today", "  回购 ", "admin", null);

        assertEquals(1, llm.calls, "同内容不同写法不该再调一次模型");
        assertTrue(second.cached(), "第二次应命中缓存");
        assertEquals(first.runId(), second.runId());
    }

    @Test
    @DisplayName("强制重算越过同水位线缓存：真的再调一次模型，并且这一版带过程")
    void forceBypassesCache() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));

        AgentService.Result first = service.ask("hot-today", null, "admin", null);
        AgentService.Result plain = service.ask("hot-today", null, "admin", null);
        assertEquals(1, llm.calls, "数据没变时不该再调模型");
        assertTrue(plain.cached());

        // 过程字段上线前生成的旧结论没有 steps，前端会放开「重新生成」按钮，
        // 那一下必须真的走到模型；否则用户点了没反应，只能靠换关键词绕。
        AgentService.Result forced = service.ask("hot-today", null, true, "admin", null);

        assertEquals(2, llm.calls, "force 必须真的再跑一次，不能复用空闲缓存");
        assertFalse(forced.cached());
        assertNotEquals(first.runId(), forced.runId(), "要是一条新记录，不是把旧的读回来");
        assertEquals(Strategy.NOTE_FORCED, forced.note());
        assertFalse(forced.steps().isEmpty(), "重算出来的这一版必须带过程");
    }

    @Test
    @DisplayName("一条新闻都没有时拒答，理由是 no-data 而不是 min-hits")
    void rejectsWhenNoData() {
        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertEquals("rejected", r.status());
        assertEquals("no-data", r.reason());
        assertEquals(0, llm.calls);
    }

    @Test
    @DisplayName("模型输出带 ```json 围栏也能解析，引用落库")
    void parsesFencedJson() {
        List<Long> ids = seed(6);
        llm.content = "```json\n" + answerWith(ids.subList(0, 2)) + "\n```";

        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertEquals("succeeded", r.status());
        assertEquals(1, llm.calls);
        assertNotNull(r.answer());
        assertEquals(1, r.answer().bullets().size());
        assertEquals(ids.subList(0, 2), r.answer().bullets().get(0).refs());

        String citations = jdbc.queryForObject(
                "SELECT citations_json FROM agent_run WHERE id = ?", String.class, r.runId());
        assertTrue(citations.contains(String.valueOf(ids.get(0))), "引用要落库，否则前端无法回链原文");
        assertTrue(r.tokensIn() > 0 && r.tokensOut() > 0, "成功的调用必须如实记 token");
    }

    @Test
    @DisplayName("越界引用被剥掉；一条结论的引用全越界就丢整条，但不影响其它结论")
    void dropsOutOfRangeRefs() {
        List<Long> ids = seed(6);
        llm.content = """
                {"title":"今日主线","bullets":[
                  {"text":"回购注销增多，对每股收益有支撑","refs":[%d, 999999]},
                  {"text":"这条的引用全是编的","refs":[888888, 777777]},
                  {"text":"纯文字没有引用","refs":[]}
                ],"insufficient":false}
                """.formatted(ids.get(0));

        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertEquals("succeeded", r.status());
        assertEquals(1, r.answer().bullets().size(), "两条没有有效引用的结论必须被丢掉");
        assertEquals(List.of(ids.get(0)), r.answer().bullets().get(0).refs(),
                "编造的 id 要剥掉，真实的保留");
        // 部分降级仍然算成功：一万 token 已经花了，能用的部分不该一起扔掉
        assertFalse(r.cached());
    }

    @Test
    @DisplayName("bullets 全空判 rejected，且不写成功记录 —— 下次点击必须能重试")
    void emptyBulletsRejectedAndRetryable() {
        seed(6);
        llm.content = "{\"title\":\"今日主线\",\"bullets\":[],\"insufficient\":true}";

        AgentService.Result first = service.ask("hot-today", null, "admin", null);
        assertEquals("rejected", first.status());
        assertEquals("empty-answer", first.reason());
        assertEquals(1, llm.calls);
        assertTrue(first.tokensIn() > 0, "钱已经花了，token 要如实记，否则账单对不上");

        // 关键：如果这行被标成 succeeded，下一次点击会命中"没有结论的成功记录"，永远拿不到结果
        assertEquals("rejected", jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id = ?", String.class, first.runId()));

        llm.content = answerWith(List.of(jdbc.queryForObject(
                "SELECT MIN(id) FROM news", Long.class)));
        AgentService.Result second = service.ask("hot-today", null, "admin", null);
        assertEquals(2, llm.calls, "上一次是 rejected，不算缓存，必须重新调用");
        assertEquals("succeeded", second.status());
    }

    @Test
    @DisplayName("同一水位线连点只花一次钱，第二次直接返回缓存")
    void sameWatermarkServedFromCache() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));

        AgentService.Result first = service.ask("hot-today", null, "admin", null);
        AgentService.Result second = service.ask("hot-today", null, "admin", null);

        assertEquals("succeeded", first.status());
        assertFalse(first.cached());
        assertEquals("succeeded", second.status());
        assertTrue(second.cached(), "数据没变，第二次必须走缓存");
        assertEquals(1, llm.calls, "第二次不该产生任何调用");
        assertEquals(first.runId(), second.runId(), "缓存返回的是同一次 run");
        assertEquals(first.tokensIn(), second.tokensIn());
        assertEquals(Strategy.NOTE_SAME, second.note(),
                "命中缓存这条路根本没走到 Strategy.decide，note 要单独给，否则浮层无话可说");
    }

    @Test
    @DisplayName("只多 1 条时不重算：返回上一版，并说明为什么没重算")
    void oneNewItemKeepsCache() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));
        AgentService.Result first = service.ask("hot-today", null, "admin", null);

        seed(1);   // 水位线确实前进了，但只多一条
        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertEquals(1, llm.calls, "1 条新闻不值得重排一次主线，模型一次都不该调");
        assertTrue(r.cached());
        assertEquals(first.runId(), r.runId(), "返回的必须是上一版");
        assertTrue(r.note() != null && r.note().contains("新增 1 条"),
                "必须告诉用户为什么没重算，否则会被当成「点了没用」：" + r.note());
    }

    @Test
    @DisplayName("多 3 条时走增量：base 指向那次 full，提示词带旧结论")
    void threeNewItemsRunDelta() {
        // 90 条而不是 6 条：L1 上限 80，总量不到上限时全量里每条都有正文，
        // 增量反而要为旧标题和证据额外付钱（实测 60 条时贵 6%），会被成本闸降级
        List<Long> ids = seed(90);
        llm.content = answerWith(ids.subList(0, 2));
        AgentService.Result full = service.ask("hot-today", null, "admin", null);

        List<Long> more = seed(3);
        llm.content = answerWith(more);
        AgentService.Result d = service.ask("hot-today", null, "admin", null);

        assertEquals(2, llm.calls, "新增 3 条到了阈值，必须真的跑一次");
        assertEquals("delta", d.strategy(), "判定理由是：" + d.note());
        assertFalse(d.cached());
        assertEquals(full.runId(), jdbc.queryForObject(
                        "SELECT base_run_id FROM agent_run WHERE id = ?", String.class, d.runId()),
                "base 必须指向那次 full");

        String prompt = llm.lastUser;
        assertTrue(prompt.contains("<previous>"), "缺旧结论会写成增量播报");
        assertTrue(prompt.contains("<previous_titles>"), "缺旧标题全貌会重复提旧事");
        assertTrue(prompt.contains("本次新增 3 条"), "digest 要说明这是增量");
    }

    @Test
    @DisplayName("证据链长、新增少时增量比全量还贵，自动降级（真实成本闸）")
    void deltaDowngradedWhenNotCheaper() {
        // 关键在于"上一版引用了多少条正文"，不是数据总量。上一版 5 条主线 × 3 引用
        // = 15 条证据正文（约 1800 字符），而新增只有 3 条，增量省下的正文抵不过证据段。
        // 2026-11-28 真实数据就是这样：60 条候选时 full 3542 token、delta 3770。
        List<Long> ids = seed(20);
        llm.content = answerWithGroups(List.of(
                ids.subList(0, 3), ids.subList(3, 6), ids.subList(6, 9),
                ids.subList(9, 12), ids.subList(12, 15)));
        service.ask("hot-today", null, "admin", null);

        List<Long> more = seed(3);
        llm.content = answerWith(more);
        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertEquals("full", r.strategy(), "增量更长时不该硬走增量");
        assertEquals(AgentService.NOTE_COST_GATE, r.note(), "降级的原因要如实告诉用户");
        assertEquals(2, llm.calls, "该调还是要调，只是换了形态");
    }

    @Test
    @DisplayName("连续增量仍指向最初那次 full —— 不链式")
    void deltaNeverChains() {
        List<Long> ids = seed(90);
        llm.content = answerWith(ids.subList(0, 2));
        AgentService.Result full = service.ask("hot-today", null, "admin", null);

        List<Long> a = seed(3);
        llm.content = answerWith(a);
        AgentService.Result d1 = service.ask("hot-today", null, "admin", null);

        List<Long> b = seed(3);
        llm.content = answerWith(b);
        AgentService.Result d2 = service.ask("hot-today", null, "admin", null);

        assertEquals("delta", d1.strategy(), "判定理由是：" + d1.note());
        assertEquals("delta", d2.strategy(), "判定理由是：" + d2.note());
        assertEquals(full.runId(), jdbc.queryForObject(
                        "SELECT base_run_id FROM agent_run WHERE id = ?", String.class, d2.runId()),
                "第三次的 base 仍是最初那次 full：链式压缩三代之后主线会变成正确的废话");
    }

    @Test
    @DisplayName("增量只出 1 条且新增不足 5 → 回退上一版，但 token 如实记")
    void suspectDeltaFallsBack() {
        List<Long> ids = seed(90);
        // 上一版是 4 条主线，这是正常形态
        llm.content = answerWithGroups(List.of(
                ids.subList(0, 1), ids.subList(1, 2), ids.subList(2, 3), ids.subList(3, 4)));
        AgentService.Result full = service.ask("hot-today", null, "admin", null);

        List<Long> more = seed(3);
        llm.content = answerWith(more);   // 增量只出 1 条：比上一版少一半以上，可疑
        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertTrue(r.cached(), "回退给上一版，对用户按缓存呈现");
        assertEquals(full.runId(), r.runId());
        assertTrue(r.note() != null && r.note().contains("回退"), r.note());

        Integer tokens = jdbc.queryForObject(
                "SELECT tokens_in FROM agent_run WHERE error LIKE 'suspect-delta%'", Integer.class);
        assertTrue(tokens != null && tokens > 0,
                "钱已经花了，记账必须如实 —— 记 0 会让账单和库里的账永远对不上");
    }

    @Test
    @DisplayName("配额用完直接 429，不取数、不调用")
    void quotaCapped() {
        seed(6);
        props.getAgent().setDailyCap(0);

        AgentException e = assertThrows(AgentException.class,
                () -> service.ask("hot-today", null, "admin", null));

        assertEquals(429, e.status());
        assertEquals("CAPPED", e.code());
        assertEquals(0, llm.calls);
    }

    @Test
    @DisplayName("未知模板 400，且列出可用模板")
    void unknownTemplate() {
        AgentException e = assertThrows(AgentException.class,
                () -> service.ask("no-such-template", null, "admin", null));

        assertEquals(400, e.status());
        assertEquals("NO_TEMPLATE", e.code());
        assertTrue(e.getMessage().contains("hot-today"), e.getMessage());
        assertEquals(0, llm.calls);
    }

    @Test
    @DisplayName("模型报错标 failed，token 记 0（异常路径拿不到 usage，不编造）")
    void modelFailureMarksFailed() {
        seed(6);
        llm.failure = new IllegalStateException("网关 502");

        AgentException e = assertThrows(AgentException.class,
                () -> service.ask("hot-today", null, "admin", null));

        assertEquals(502, e.status());
        assertEquals("MODEL_ERROR", e.code());
        Integer tokens = jdbc.queryForObject(
                "SELECT tokens_in FROM agent_run WHERE status='failed'", Integer.class);
        assertEquals(0, tokens);
        // 失败不扣配额：成功的行数还是 0
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run WHERE status='succeeded'", Long.class));
    }

    @Test
    @DisplayName("唯一索引在 running 阶段就挡住第二次生成（这一层才真正省钱）")
    void duplicateWatermarkRejectedAtStore() {
        seed(6);
        AgentRunStore store = new AgentRunStore(jdbc);

        assertTrue(store.insertRunning("r_a", "hot-today", "{}", "admin", "2026-11-27", "full", null, 100));
        // 关键断言：r_a 还在 running（没调完模型），第二个请求就插不进来了。
        // 旧写法要等到 markSucceeded 才挡，那时一万 token 已经花掉
        assertFalse(store.insertRunning("r_b", "hot-today", "{}", "admin", "2026-11-27", "full", null, 100),
                "同键的 running 行必须互斥");
        assertTrue(store.markSucceeded("r_a", "{}", "[]", 10, 100, 20, "m", 900));
        // succeeded 之后同样挡住新的 running
        assertFalse(store.insertRunning("r_c", "hot-today", "{}", "admin", "2026-11-27", "full", null, 100),
                "同键的 succeeded 行也必须互斥");

        // 失败不占用水位线：另一批数据失败过一次，必须还能重算
        store.insertRunning("r_fail", "hot-today", "{}", "admin", "2026-11-27", "full", null, 200);
        store.markFailed("r_fail", "模型超时", 0, 0, 100);
        store.insertRunning("r_ok", "hot-today", "{}", "admin", "2026-11-27", "full", null, 200);
        assertTrue(store.markSucceeded("r_ok", "{}", "[]", 10, 100, 20, "m", 900),
                "失败过的水位线必须能重算，否则一次超时就把这批数据永久锁死");
    }

    @Test
    @DisplayName("重启会把残留的 running 复位，避免界面永远显示生成中")
    void resetStuckOnStartup() {
        AgentRunStore store = new AgentRunStore(jdbc);
        store.insertRunning("r_stuck", "hot-today", "{}", "admin", "2026-11-27", "full", null, 1);

        int fixed = store.resetStuck();

        assertEquals(1, fixed);
        assertEquals("failed", jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id='r_stuck'", String.class));
    }

    @Test
    @DisplayName("拼给模型的 user prompt 含真实新闻块与分层声明")
    void promptContainsNewsBlock() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 1));

        service.ask("hot-today", null, "admin", null);

        String user = llm.lastUser;
        assertNotNull(user);
        assertTrue(user.contains("<news>"), "标签由模板提供，缺了模型会把正文当指令");
        assertTrue(user.contains("[[" + ids.get(0) + "]]"), "每行必须带 id，引用才有依据");
        assertTrue(user.contains("条含正文"), "分层声明必须在，否则模型会拿只有标题的条目编细节");
        assertTrue(!user.contains("{{"), "占位符必须全部替换掉：" + user.substring(0, Math.min(200, user.length())));
    }

    @Test
    @DisplayName("模板自带的关键词真的生效：算力模板不会把当天的回购公告一起喂进去")
    void templateKeywordsNarrowRetrieval() {
        long start = LocalDate.now(ZONE).atStartOfDay(ZONE).toEpochSecond() + 60;
        long a = TestDb.insert(jdbc, "nvda-a", "英伟达发布新一代 GB200，算力链集体走强",
                "公司称新平台已在台积电投产，服务器代工环节同步受益，具体排产时间未披露。", start + 1);
        long b = TestDb.insert(jdbc, "nvda-b", "英伟达上调 H100 出货预期，台积电受关注",
                "供应链人士称订单能见度延长至明年二季度，先进封装产能仍然紧张。", start + 2);
        seed(4);  // 四条与算力链无关的回购公告

        llm.content = answerWith(List.of(a, b));
        AgentService.Result r = service.ask("nvda-bullish", null, "admin", null);

        assertEquals("succeeded", r.status());
        assertNotNull(llm.lastUser);
        assertTrue(llm.lastUser.contains("[[" + a + "]]"), "命中关键词的条目必须在 prompt 里");
        assertTrue(llm.lastUser.contains("[[" + b + "]]"));
        // 这条断言盯的是一个真实出过的 bug：keywords 解析了却没人用，
        // 结果模板说"只分析下跌"，实际喂进去的是当天全部 139 条
        assertFalse(llm.lastUser.contains("回购"), "模板关键词必须把无关条目挡在外面：" + llm.lastUser);
    }

    @Test
    @DisplayName("主题不符直接拒答：有下跌新闻但不是 A 股，0 次调用")
    void offTopicRejectedWithoutCallingModel() {
        long start = LocalDate.now(ZONE).atStartOfDay(ZONE).toEpochSecond() + 60;
        TestDb.insert(jdbc, "tr-1", "土耳其主要银行指数暴跌 3.2%",
                "该国央行意外加息后银行股承压，市场担忧资产质量进一步恶化，里拉汇率同步走弱。", start + 1);
        TestDb.insert(jdbc, "us-1", "美股 Vertex 暴跌 18%",
                "公司公布的三期临床数据不及预期，多家投行下调目标价，成交量创近一年新高。", start + 2);
        TestDb.insert(jdbc, "dram-1", "DRAM 现货价格大跌",
                "渠道商抛货导致合约价松动，存储原厂库存水位上升，短期价格仍有下探空间。", start + 3);

        AgentService.Result r = service.ask("a-share-drop", null, "admin", null);

        assertEquals("rejected", r.status());
        assertEquals("off-topic", r.reason(), "材料够（3 条）但主题不符，理由必须是 off-topic 而不是 min-hits");
        assertEquals(0, llm.calls, "答非所问的调用是最贵的一种浪费：钱花了，用户还得自己发现");
        assertEquals(0, r.tokensIn());
    }

    @Test
    @DisplayName("昨天的成功结果不能当今天的缓存：水位线相同也必须重跑")
    void yesterdayResultIsNotTodayCache() {
        List<Long> ids = seed(6);
        long watermark = jdbc.queryForObject("SELECT MAX(id) FROM news", Long.class);
        AgentRunStore store = new AgentRunStore(jdbc);
        String params = "{\"templateId\":\"hot-today\",\"window\":\"1d\"}";
        store.insertRunning("r_yesterday", "hot-today", params, "admin", "2000-01-01", "full", null, watermark);
        assertTrue(store.markSucceeded("r_yesterday", "{}", "[]", 6, 100, 20, "m", 900),
                "跨天的同一水位线必须允许各自成功，否则今天就永远写不进结果");

        llm.content = answerWith(ids.subList(0, 2));
        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertEquals(1, llm.calls, "昨天的主线不能顶替今天的主线（跨天必 full）");
        assertFalse(r.cached());
    }

    @Test
    @DisplayName("上一次是 delta 也算缓存：数据没变就不再扣一次费")
    void deltaResultServesAsCache() {
        List<Long> ids = seed(6);
        long watermark = jdbc.queryForObject("SELECT MAX(id) FROM news", Long.class);
        String params = "{\"templateId\":\"hot-today\",\"window\":\"1d\"}";
        String today = LocalDate.now(ZONE).toString();

        // 摆出"10 点跑过一次 delta"的局面：水位线已经被 delta 推进到当前值
        AgentRunStore store = new AgentRunStore(jdbc);
        store.insertRunning("r_delta", "hot-today", params, "admin", today, "delta", "r_full", watermark);
        assertTrue(store.markSucceeded("r_delta", answerWith(ids.subList(0, 1)), "[]",
                6, 8_644, 300, "m", 4_000));

        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        // 这是 §10.2 那个缝：只找"最近一次 full"的话，这里的水位线与 full 不等，
        // 会被判成"要再跑一次 delta"，数据一个字没变却又扣一次钱
        assertTrue(r.cached(), "delta 的输出形态和 full 一样，必须能当缓存");
        assertEquals("r_delta", r.runId());
        assertEquals(0, llm.calls);
        assertEquals("delta", r.strategy());
    }

    // ---------------------------------------------------------------- 并发（A+B）

    @Test
    @DisplayName("并发点击：只调一次模型，两个请求拿到同一份结果")
    void concurrentClicksCallModelOnce() throws Exception {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 2));
        llm.delayMs = 400;   // 让第二个线程真的落在第一个的执行期间

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<AgentService.Result> click = () -> {
            go.await();
            return service.ask("hot-today", null, "admin", null);
        };
        Future<AgentService.Result> f1 = pool.submit(click);
        Future<AgentService.Result> f2 = pool.submit(click);
        go.countDown();

        AgentService.Result r1 = f1.get(20, TimeUnit.SECONDS);
        AgentService.Result r2 = f2.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, llm.calls, "同键的并发只能调一次模型 —— 这条断言就是方案 A+B 的全部意义");
        assertEquals(r1.runId(), r2.runId(), "两个人看到的必须是同一次 run");
        assertEquals("succeeded", r1.status());
        assertEquals("succeeded", r2.status());
        assertTrue(r1.cached() ^ r2.cached(), "恰好一个是 leader（非缓存），一个是 follower（按缓存呈现）");
    }

    @Test
    @DisplayName("僵尸占用（超时仍 running）会被释放，不至于把这个水位线永久锁死")
    void staleRunningIsReleased() {
        List<Long> ids = seed(6);
        long watermark = jdbc.queryForObject("SELECT MAX(id) FROM news", Long.class);
        String today = LocalDate.now(ZONE).toString();
        String params = "{\"templateId\":\"hot-today\",\"window\":\"1d\"}";

        AgentRunStore store = new AgentRunStore(jdbc);
        assertTrue(store.insertRunning("r_zombie", "hot-today", params, "admin", today, "full", null, watermark));
        // 模拟"容器在生成途中被 kill"：行留在 running，创建时间停在 10 分钟前
        jdbc.update("UPDATE agent_run SET created_at = created_at - 600 WHERE id='r_zombie'");

        llm.content = answerWith(ids.subList(0, 1));
        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertEquals("succeeded", r.status(), "僵尸行必须能被抢走，否则一次崩溃就锁死这个水位线");
        assertEquals(1, llm.calls);
        assertEquals("failed", jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id='r_zombie'", String.class));
    }

    @Test
    @DisplayName("别人正在生成时不重复调模型，等不到就明确 BUSY")
    void busyWhenPeerIsRunning() {
        seed(6);
        long watermark = jdbc.queryForObject("SELECT MAX(id) FROM news", Long.class);
        props.getAgent().setBusyWaitMs(300);
        String params = "{\"templateId\":\"hot-today\",\"window\":\"1d\"}";

        new AgentRunStore(jdbc).insertRunning("r_live", "hot-today", params, "admin",
                LocalDate.now(ZONE).toString(), "full", null, watermark);

        AgentException e = assertThrows(AgentException.class,
                () -> service.ask("hot-today", null, "admin", null));

        assertEquals(503, e.status());
        assertEquals("BUSY", e.code());
        assertEquals(0, llm.calls, "别人在跑的时候，自己一次都不该调");
    }

    @Test
    @DisplayName("别人正在生成时，材料不足仍能正常拒答 —— 拒答和跑批是两件事")
    void rejectedNotBlockedByPeerRunning() {
        seed(2);   // hot-today 的 min-hits 是 5
        long watermark = jdbc.queryForObject("SELECT MAX(id) FROM news", Long.class);
        String params = "{\"templateId\":\"hot-today\",\"window\":\"1d\"}";

        new AgentRunStore(jdbc).insertRunning("r_live", "hot-today", params, "admin",
                LocalDate.now(ZONE).toString(), "full", null, watermark);

        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        // rejected 不在唯一索引条件里，所以它写得进去。走 running 那条路会撞索引 → 500，
        // 把"今天新闻就少"这个 0 成本的结论变成一个错误
        assertEquals("rejected", r.status());
        assertEquals("min-hits", r.reason());
        assertEquals(0, llm.calls);
    }

    /**
     * 假模型：只记录被调了几次、最后收到的 prompt，返回预设内容。
     *
     * <p>{@code calls} 是 volatile 且 {@code chat} 整体同步的：并发用例里两个线程会同时打进来，
     * 不加这两处，测出来的"调用了几次"是不可信的 —— 而这条断言正是整个并发方案的核心。
     */
    static final class FakeLlm implements AgentLlm {

        private volatile int calls;
        private String content = "{}";
        private String lastUser;
        private Exception failure;
        /** 模拟"模型要跑几百毫秒"，好让第二个线程真的落在第一个的执行期间 */
        private long delayMs;
        private int tokensIn = 8_600;
        private int tokensOut = 380;
        /** 模拟推理模型吐思考过程；null = 不吐 */
        private String reasoning = null;

        @Override
        public synchronized Reply chat(String system, String user) throws Exception {
            calls++;
            lastUser = user;
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return new Reply(content, tokensIn, tokensOut, "fake-model", 120, reasoning, 0);
        }

        @Override
        public synchronized Reply chatStream(String system, String user,
                                              java.util.function.Consumer<String> onReasoning) throws Exception {
            calls++;
            lastUser = user;
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            // 模拟流式：把思考切成几段逐段吐
            if (reasoning != null && onReasoning != null) {
                int step = Math.max(1, reasoning.length() / 3);
                for (int i = 0; i < reasoning.length(); i += step) {
                    onReasoning.accept(reasoning.substring(i, Math.min(i + step, reasoning.length())));
                }
            }
            return new Reply(content, tokensIn, tokensOut, "fake-model", 120, reasoning, 0);
        }

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public String modelName() {
            return "fake-model";
        }
    }

    @Test
    @DisplayName("答案里没有 answer_json 时 toResult 不抛异常，返回空答案而不是 500")
    void corruptedAnswerDoesNotCrash() {
        List<Long> ids = seed(6);
        llm.content = answerWith(ids.subList(0, 1));
        service.ask("hot-today", null, "admin", null);

        // 人为破坏：模拟手工改库或旧版本数据
        jdbc.update("UPDATE agent_run SET answer_json = 'not json' WHERE status='succeeded'");

        AgentService.Result r = service.ask("hot-today", null, "admin", null);

        assertEquals("succeeded", r.status(), "缓存命中仍应返回，而不是因为一条坏数据 500");
        assertNull(r.answer());
    }
}
