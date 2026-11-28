package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 离线体检工具：不调模型、不花钱，直接拿真实库跑一遍「取数 → 分类合并 → 打分打包」。
 *
 * <p>存在的理由：文档里的 token 预算、L1/L2 比例、剔除率全是从历史数据算出来的估算，
 * 而估算最容易在两种情况下失效 —— 数据分布变了（比如某天突然来了 600 条），
 * 或者规则写错了（打分阈值偏了，L1 只剩 3 条）。这两种情况在真正花钱之前就该被发现。
 *
 * <p>用法（不依赖 exec 插件）：
 * <pre>
 * JAVA_HOME=/opt/homebrew/opt/openjdk mvn -q -DskipTests compile
 * JAVA_HOME=/opt/homebrew/opt/openjdk mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
 * /opt/homebrew/opt/openjdk/bin/java -cp "target/classes:$(cat /tmp/cp.txt)" \
 *     com.herc.clsnews.agent.PackProbe data/news.db 2026-11-27
 * </pre>
 *
 * <p>参数都可省：默认读 {@code data/news.db}、默认按今天（Asia/Shanghai）算窗口。
 */
public final class PackProbe {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private PackProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path db = Path.of(args.length > 0 ? args[0] : "data/news.db");
        if (!Files.isReadable(db)) {
            System.err.println("读不到数据库：" + db.toAbsolutePath());
            System.err.println("用法：PackProbe [db 路径] [yyyy-MM-dd]");
            System.exit(2);
        }
        LocalDate day = args.length > 1
                ? LocalDate.parse(args[1])
                : LocalDate.now(ZONE);

        long from = day.atStartOfDay(ZONE).toEpochSecond();
        long to = day.plusDays(1).atStartOfDay(ZONE).toEpochSecond();

        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Retrieval retrieval = new Retrieval(jdbc);

        List<NewsRow> rows = retrieval.fetch(from, to, List.of());
        PackResult r = Packer.pipeline(rows);

        System.out.println("=== 打包体检 " + day + " ===");
        System.out.printf("DB: %s   窗口: %s ~ %s (+08:00)%n", db,
                day.atStartOfDay(ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                day.plusDays(1).atStartOfDay(ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        System.out.println("watermark(max id) = " + retrieval.watermark());
        System.out.println();
        System.out.println(r.summary());
        if (!r.promoByTag().isEmpty()) {
            System.out.println("剔除栏目明细：" + r.promoByTag());
        }
        System.out.println(r.digest());
        System.out.printf("预估输入 %d token（硬闸 %d，占 %.0f%%）%n",
                r.estTokens(), r.maxTokensIn(), 100.0 * r.estTokens() / r.maxTokensIn());

        // S1.2 的核心体检：预估与实际拼接必须对得上。
        // 这两个数字一旦分叉，界面报价、硬闸、成本对照表就全部失去意义。
        String block = PromptBuilder.newsBlock(r);
        long sum = r.all().stream().mapToLong(PackedNews::promptChars).sum();
        int realTokens = PromptBuilder.tokens(block.length()) + Packer.PROMPT_OVERHEAD;
        System.out.printf("真实拼接 %d 字符（逐条累加 %d，差 %d）→ %d token%n",
                block.length(), sum, block.length() - sum, realTokens);
        System.out.printf("口径偏差：%d token（%.2f%%）%n",
                realTokens - r.estTokens(),
                100.0 * (realTokens - r.estTokens()) / Math.max(1, r.estTokens()));
        System.out.println();

        System.out.println("--- 分类分布（候选集内）---");
        Map<NewsClass.Kind, Integer> dist = new EnumMap<>(NewsClass.Kind.class);
        for (PackedNews p : r.all()) {
            dist.merge(p.kind(), 1, Integer::sum);
        }
        System.out.println("  " + dist);
        System.out.println();

        System.out.println("--- L1 前 10（拿正文，按分数）---");
        print(r.l1(), 10, true);
        System.out.println();
        System.out.println("--- L2 前 10（仅标题，按时间倒序）---");
        print(r.l2(), 10, false);
    }

    private static void print(List<PackedNews> list, int n, boolean withBody) {
        int i = 0;
        for (PackedNews p : list) {
            if (i++ >= n) {
                break;
            }
            String title = p.title();
            if (title.length() > 60) {
                title = title.substring(0, 60) + "…";
            }
            System.out.printf("  %5d  score=%-2d  %-6s  %s%s%n",
                    p.id(), p.score(), p.kind(),
                    title,
                    withBody && p.content() != null ? "  [正文 " + p.content().length() + " 字]" : "");
        }
    }
}
