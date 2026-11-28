package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分类与合并的规则测试。
 *
 * <p>这一层必须全是离线断言：它是整条 Agent 流水线里唯一"改一个词就改变成本的环节"，
 * 如果验证要花钱调模型，规则就永远调不准 —— 没人会为了试一个正则跑一次真调用。
 */
class ClassifyTest {

    private static NewsRow row(long id, String title, String content, long t) {
        return new NewsRow(id, title, content, t, "C");
    }

    @Test
    @DisplayName("以【开头的条目一律剔除，未收录的新栏目同样剔除")
    void promoRemoved() {
        List<NewsRow> rows = List.of(
                row(1, "【盘中宝】某公司订单饱满，机构密集调研", 长正文(), 1000),
                row(2, "【某个从没见过的栏目】今日重磅消息汇总", 长正文(), 1100),
                row(3, "正常的一条新闻，某公司拟回购2亿元", 长正文(), 1200));

        Classifier.Result r = Classifier.classify(rows);

        assertEquals(2, r.promoDropped(), "两个【开头的都应该被剔除，不看栏目名");
        assertEquals(1, r.items().size());
        assertEquals(3, r.items().get(0).row().id());
        // 剔除是"不进候选"，不是"删库"，原始数据仍然在 —— 这条边界不能含糊
        assertEquals(3, r.fetched());
    }

    @Test
    @DisplayName("同一标的同一方向的连续播报合成一条，保留幅度最大的，并标注播报次数")
    void quotesMerged() {
        List<NewsRow> rows = List.of(
                row(1, "恒生科技指数跌幅扩大至2%", "截至10:12，报4821点。", 1000),
                row(2, "恒生科技指数跌幅扩大至3%", "截至11:40，报4775点。", 1100),
                row(3, "恒生科技指数跌幅扩大至4%", "截至14:05，报4726点。", 1200));

        Classifier.Result r = Classifier.classify(rows);

        assertEquals(1, r.items().size(), "三条同标的同方向，应合并为一条");
        Classified c = r.items().get(0);
        assertEquals(3, c.mergedCount());
        assertEquals(2, r.quoteMerged(), "被合并掉的是 2 条（组大小 3 减 1）");
        // 取幅度最大的那条，而不是最新的 —— 「4%」比「2%」信息量大
        assertTrue(c.row().title().contains("4%"), "应保留幅度最大的一条：" + c.row().title());
        assertEquals(3, c.row().id());
        assertTrue(c.displayTitle().endsWith("（当日 3 次播报）"), "合并后要标出次数：" + c.displayTitle());
    }

    @Test
    @DisplayName("涨和跌不能合并：同一标的来回震荡是信号，不是重复")
    void upAndDownNotMerged() {
        List<NewsRow> rows = List.of(
                row(1, "现货白银涨3%", "报每盎司32.1美元。", 1000),
                row(2, "现货白银跌2%", "报每盎司31.2美元。", 1100));

        Classifier.Result r = Classifier.classify(rows);

        assertEquals(2, r.items().size(), "方向不同必须各自成条");
        assertEquals(0, r.quoteMerged());
    }

    @Test
    @DisplayName("正文极短的条目即使分数很高也只给标题")
    void terseNeverGetsBody() {
        List<NewsRow> rows = List.of(
                row(1, "宁德时代中标50亿元储能项目", "刚刚。", 1000));

        PackResult p = Packer.pipeline(rows);

        assertEquals(NewsClass.Kind.TERSE, p.l2().get(0).kind());
        assertTrue(p.l1().isEmpty(), "正文只有 2 个字，给正文纯属浪费预算");
        assertTrue(p.l2().get(0).score() >= 3, "分数高与给不给正文是两件事");
    }

    @Test
    @DisplayName("海外非财经条目降权但不删除；正文过短时归 TERSE，降权仍然生效")
    void overseasPenalised() {
        // 正文 ≥ 40 字才会被判 OVERSEAS（TERSE 判定在它之前，这是设计里写明的取舍）
        NewsRow longBody = row(1, "某国军舰穿越海峡",
                "当地时间今日上午，该国海军对外表示，一艘军舰按计划通过相关海域，全程未发生异常情况，"
                        + "后续将按原定安排继续航行。", 1000);
        Classified c = Classifier.classify(List.of(longBody)).items().get(0);
        assertEquals(NewsClass.Kind.OVERSEAS, c.kind());
        assertTrue(Scorer.score(c.row()) < 0, "应被扣 2 分，实际 " + Scorer.score(c.row()));

        // 正文 38 字 → 归 TERSE。枚举上看不出该降权，但打分不依赖枚举，所以仍然 -2
        NewsRow shortBody = row(2, "某国军舰穿越海峡",
                "当地时间今日上午，该国海军表示，一艘军舰按计划通过相关海域，未发生异常情况。", 1000);
        Classified c2 = Classifier.classify(List.of(shortBody)).items().get(0);
        assertEquals(NewsClass.Kind.TERSE, c2.kind(), "短正文优先归 TERSE，这是已知取舍");
        assertTrue(Scorer.score(c2.row()) < 0, "降权必须独立于路由枚举，否则这类条目会漏扣");

        // 降权不等于剔除
        assertEquals(1, Classifier.classify(List.of(longBody)).items().size());
    }

    @Test
    @DisplayName("有具体金额与公司行为的正常条目不被误判成行情播报，并进入 L1")
    void normalNotMisclassified() {
        List<NewsRow> rows = List.of(
                row(1, "某科技公司拟回购2亿元股份", 长正文(), 1000));

        PackResult p = Packer.pipeline(rows);

        assertEquals(NewsClass.Kind.NORMAL, p.l1().get(0).kind());
        assertTrue(p.l1().get(0).score() >= Scorer.L1_THRESHOLD);
        assertFalse(p.l1().get(0).content() == null, "L1 必须带正文");
    }

    @Test
    @DisplayName("剔除时按栏目名计数，用来发现「某天突然少了一批」")
    void promoCountedByTag() {
        List<NewsRow> rows = List.of(
                row(1, "【盘中宝】公司A", 长正文(), 1000),
                row(2, "【盘中宝】公司B", 长正文(), 1100),
                row(3, "【风口研报·公司】公司C", 长正文(), 1200),
                row(4, "一条正常新闻", 长正文(), 1300));

        Classifier.Result r = Classifier.classify(rows);

        assertEquals(3, r.promoDropped());
        assertEquals(2, r.promoByTag().get("【盘中宝】"));
        assertEquals(1, r.promoByTag().get("【风口研报·公司】"));
        // 计数不能只躺在 Result 里，要真的出现在给运维看的那行摘要上
        PackResult p = Packer.withClassify(Packer.pack(r.items()), r);
        assertTrue(p.summary().contains("【盘中宝】"), "摘要里要能看到高频栏目：" + p.summary());
    }

    /** 长度 60 以上的中文正文，用来绕开 TERSE 判定。 */
    private static String 长正文() {
        return "公司今日晚间发布公告称，本次交易已经董事会审议通过，尚需提交股东大会审议。"
                + "交易完成后，公司主营业务结构将进一步优化，具体影响以审计报告为准。";
    }
}
