package com.herc.clsnews.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.herc.clsnews.ai.LlmKey;
import com.herc.clsnews.config.AppProps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 真模型验收探针：跑一次完整的主线生成，把过程与花费打出来。
 *
 * <p>存在的理由：S1.2 的验收有两半 —— 假模型跑通分支（已由 {@code AgentServiceTest} 覆盖），
 * 和<b>真模型能不能出 3–5 条带真实引用的主线</b>。后者只能真调一次，而 S1.4 的接口还没做。
 * 没有这个探针，就得等接口做完才能知道提示词写没写对 —— 而提示词错了越晚发现越贵。
 *
 * <p>它直接构造依赖、不走 Spring 容器：起容器会跑 {@code @Scheduled} 的采集任务，
 * 验收一次主线却顺带抓 30 条新闻，污染数据还看不出是谁干的。
 *
 * <p>用法（会真的花钱，一次约 1 万 token，几分钱）：
 * <pre>
 * JAVA_HOME=/opt/homebrew/opt/openjdk mvn -q -DskipTests compile
 * JAVA_HOME=/opt/homebrew/opt/openjdk mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
 * /opt/homebrew/opt/openjdk/bin/java -cp "target/classes:$(cat /tmp/cp.txt)" \
 *     com.herc.clsnews.agent.AgentProbe data/news.db hot-today
 * </pre>
 *
 * <p>第二次跑同一批数据会直接命中缓存（0 token），这本身也是一条验收项。
 */
public final class AgentProbe {

    private AgentProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path db = Path.of(args.length > 0 ? args[0] : "data/news.db");
        String templateId = args.length > 1 ? args[1] : "hot-today";
        if (!Files.isReadable(db)) {
            System.err.println("读不到数据库：" + db.toAbsolutePath());
            System.err.println("用法：AgentProbe [db 路径] [templateId]");
            System.exit(2);
        }

        ObjectMapper mapper = new ObjectMapper();
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        AppProps props = new AppProps();
        // 第三个参数可覆盖输出上限。dry-run 调参用：max_tokens 该给多少只能试出来，
        // 而每试一次都花钱，改代码重编译只会让试错变慢
        if (args.length > 2) {
            props.getAgent().setMaxTokensOut(Integer.parseInt(args[2]));
            System.out.println("（覆盖 max-tokens-out = " + args[2] + "）");
        }
        AgentCatalog catalog = new AgentCatalog();
        if (!catalog.ready()) {
            System.err.println("模板加载失败：" + catalog.loadError());
            System.exit(3);
        }

        Retrieval retrieval = new Retrieval(jdbc);
        AgentRunStore runs = new AgentRunStore(jdbc);
        DeepSeekAgentLlm llm = new DeepSeekAgentLlm(props, mapper, new LlmKey(props, mapper));

        if (!llm.ready()) {
            System.err.println("模型未就绪，检查 key（DEEPSEEK_API_KEY 或 ~/.codex/auth.json）");
            System.exit(4);
        }

        AgentService service = new AgentService(catalog, retrieval, runs, llm, new SingleFlight(), props, mapper);

        System.out.println("=== 主线验收 ===");
        System.out.println("DB: " + db.toAbsolutePath());
        System.out.println("模板: " + templateId + "  模型: " + llm.modelName());
        System.out.println("watermark = " + retrieval.watermark());
        System.out.println();

        long t0 = System.currentTimeMillis();
        AgentService.Result r;
        try {
            r = service.ask(templateId, null, "probe", null);
        } catch (AgentException e) {
            System.out.println("失败 " + e.status() + " / " + e.code() + "：" + e.getMessage());
            System.exit(1);
            return;
        }
        long ms = System.currentTimeMillis() - t0;

        System.out.println("runId     = " + r.runId());
        System.out.println("status    = " + r.status());
        System.out.println("strategy  = " + r.strategy() + (r.cached() ? "（命中缓存，0 token）" : ""));
        System.out.println("candidates= " + r.candidates());
        System.out.println("tokens    = " + r.tokensIn() + " in / " + r.tokensOut() + " out");
        System.out.println("耗时      = " + ms + " ms（含打包与落库）");
        System.out.println();

        if (r.reason() != null) {
            System.out.println("拒答理由  = " + r.reason() + "：" + r.detail());
            return;
        }
        if (r.answer() == null) {
            System.out.println("（没有可展示的答案：answer_json 为空或损坏）");
            return;
        }

        System.out.println("--- " + r.answer().title() + " ---");
        int i = 1;
        for (Answer.Bullet b : r.answer().bullets()) {
            System.out.printf("%d. %s%n", i++, b.text());
            System.out.println("   引用: " + b.refs());
        }
        System.out.println();
        System.out.println("insufficient = " + r.answer().insufficient());
        System.out.println("引用总数 = " + r.answer().allRefs().size());

        // 引用必须能回到真实新闻上，这是"可核验"的最后一步
        System.out.println();
        System.out.println("--- 引用回查（前 5 条）---");
        int n = 0;
        for (Long id : r.answer().allRefs()) {
            if (n++ >= 5) {
                break;
            }
            var rows = jdbc.queryForList("SELECT id, title FROM news WHERE id = ?", id);
            System.out.println(rows.isEmpty() ? "  " + id + " -> 不存在！" : "  " + id + " -> " + rows.get(0).get("title"));
        }
    }
}
