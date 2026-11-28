package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预估口径与实际拼接的对账测试。
 *
 * <p>为什么这个测试比别的都重要：项目里所有"这次花多少 token"的说法 ——
 * 界面上的报价、硬闸的阈值、文档里的成本对照表 —— 全部建立在
 * {@link PackedNews#promptChars()} 之上。它一旦和真实拼出来的字符串对不上，
 * 整条预算体系就是一个"看起来有数字、其实没约束"的摆设。
 *
 * <p>S1.2 开工前发现的真实偏差：前缀被当成常数 17，实际是 {@code 17 + id 位数}。
 * 600 条下少算约 1400 token（≈6%），不足以让硬闸失效，但足以让报价表失准。
 */
class PromptBuildTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 2026-11-27 14:32 +08:00，与文档里的样例一致。 */
    private static final long T = Instant.parse("2026-11-27T06:32:00Z").getEpochSecond();

    private static PackedNews l1(long id, String title, String content) {
        return new PackedNews(id, title, T, content, NewsClass.Kind.NORMAL, 6, true);
    }

    private static PackedNews l2(long id, String title) {
        return new PackedNews(id, title, T, null, NewsClass.Kind.QUOTE, -1, false);
    }

    @Test
    @DisplayName("前缀长度 = 17 + id 位数，不是常数 17")
    void prefixGrowsWithIdDigits() {
        // [[1312]] 11-27 14:32  → 17 + 4 = 21
        assertEquals(21, PromptBuilder.head(l1(1312, "标题", "正文")).length());
        // 五位 id 多一个字符：id 破万是必然的（当前库里已有 1.3 万条）
        assertEquals(22, PromptBuilder.head(l1(13120, "标题", "正文")).length());
        assertEquals(23, PromptBuilder.head(l1(131200, "标题", "正文")).length());
        assertEquals(20, PromptBuilder.head(l1(131, "标题", "正文")).length());

        assertEquals("[[1312]] 11-27 14:32 ", PromptBuilder.head(l1(1312, "标题", "正文")),
                "格式变了引用就对不上，任何改动都必须让这条红");
    }

    @Test
    @DisplayName("拼出来的长度与 promptChars 严格相等：L1、L2、空正文三种形态")
    void lineLengthEqualsEstimate() {
        List<PackedNews> cases = List.of(
                l1(1312, "白银大涨7%，贵金属链集体走强", "沪银主力合约日内涨幅扩大至7%，创年内新高。"),
                l2(1301, "纳斯达克指数收涨1.2%"),
                l1(999, "某公司公告", ""));

        for (PackedNews p : cases) {
            String line = PromptBuilder.line(p);
            assertEquals(p.promptChars(), line.length(), "口径不一致：" + line);
        }
    }

    @Test
    @DisplayName("正文里的换行被压平成空格，且总长度不变（否则预估立刻失准）")
    void newlinesFlattenedWithoutChangingLength() {
        String multi = "第一段内容。\n第二段内容。\r\n第三段内容。";
        PackedNews p = l1(1312, "多段正文的新闻", multi);

        String line = PromptBuilder.line(p);

        assertTrue(line.contains("第一段内容。 第二段内容。  第三段内容。"), "换行必须变成空格，不能删掉");
        assertEquals(p.promptChars(), line.length(), "压平操作必须 1 字符换 1 字符");
        assertEquals(2, line.chars().filter(c -> c == '\n').count(),
                "一条新闻只留两个换行：标题后、正文后");
    }

    @Test
    @DisplayName("600 条整块的字符数等于逐条累加，且 token 预估落在实测区间")
    void bulkBlockMatchesSum() {
        List<NewsRow> rows = new ArrayList<>(600);
        String title = pad("某公司拟回购2亿元股份", 28);
        String body = pad("公司表示本次事项对经营无重大影响。", 170);
        for (int i = 1; i <= 200; i++) {
            rows.add(new NewsRow(i, title, body, T + i, "C"));
        }
        for (int i = 201; i <= 600; i++) {
            rows.add(new NewsRow(i, title, "极短正文。", T + i, "C"));
        }

        PackResult r = Packer.pipeline(rows);
        String block = PromptBuilder.newsBlock(r);

        long sum = r.all().stream().mapToLong(PackedNews::promptChars).sum();
        assertEquals(sum, block.length(), "600 条整块必须与逐条累加完全相等");

        // 真实数字要看得见：S1.2 复核前文档写 1.8 万，实际是 2.5 万量级
        int tokens = PromptBuilder.tokens(block.length()) + Packer.PROMPT_OVERHEAD;
        assertTrue(tokens > 24_000 && tokens < 28_000, "600 条应在 2.4–2.8 万，实际 " + tokens);
        assertTrue(Math.abs(tokens - r.estTokens()) <= 3,
                "整块折算 " + tokens + " 与打包时的累加预估 " + r.estTokens() + " 差得太多");
    }

    @Test
    @DisplayName("行首 id 可被正则还原，引用校验才能从文本回到新闻")
    void idRoundTrip() {
        PackedNews p = l1(1312, "白银大涨7%", "正文");
        String line = PromptBuilder.line(p);

        var m = java.util.regex.Pattern.compile("^\\[\\[(\\d+)]] (\\d{2}-\\d{2} \\d{2}:\\d{2}) ")
                .matcher(line);
        assertTrue(m.find(), "行首格式必须可解析，否则引用无法回溯：" + line);
        assertEquals("1312", m.group(1));
        assertEquals("11-27 14:32", m.group(2));
    }

    private static String pad(String base, int n) {
        StringBuilder sb = new StringBuilder(base);
        while (sb.length() < n) {
            sb.append("关于本次事项的进一步说明与补充材料安排。");
        }
        return sb.substring(0, n);
    }

    /** 防止有人把 ZoneId 改成本机默认时区 —— 服务器是 UTC 的话，时间会整体差 8 小时。 */
    @Test
    @DisplayName("时间戳固定按 Asia/Shanghai 渲染，不受服务器时区影响")
    void stampUsesShanghaiZone() {
        String head = PromptBuilder.head(l1(1, "标题", "正文"));
        assertTrue(head.contains("11-27 14:32"),
                "2026-11-27T06:32Z 在 +08:00 下应是 14:32，实际 " + head);
        assertEquals(ZONE, ZoneId.of("Asia/Shanghai"));
    }
}
