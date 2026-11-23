package com.herc.clsnews.web;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIKE 模式转义。
 *
 * <p>两种错法都不会报错，只会「搜出来不对」：漏转义时搜 {@code 100%} 会命中
 * {@code 100元}（用户以为搜索坏了）；转义顺序错时反斜杠被二次转义，
 * 搜带反斜杠的词直接搜不到。所以既断言拼出来的字符串，也断言真的查库的结果 ——
 * 只断言字符串的话，SQL 里 ESCAPE 写错也测不出来。
 */
class LikePatternTest {

    @TempDir
    Path dir;

    private JdbcTemplate jdbc;
    private NewsStore store;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.create(dir);
        store = new NewsStore(jdbc);
    }

    private List<String> titles(String keyword) {
        return store.search(LikePattern.of(keyword), Long.MAX_VALUE, 50)
                .stream().map(News::title).toList();
    }

    @Test
    @DisplayName("百分号被转义，不再当通配符")
    void escapesPercent() {
        assertThat(LikePattern.of("100%")).isEqualTo("%100\\%%");
    }

    @Test
    @DisplayName("下划线被转义，不再匹配任意单字符")
    void escapesUnderscore() {
        assertThat(LikePattern.of("a_b")).isEqualTo("%a\\_b%");
    }

    @Test
    @DisplayName("反斜杠先转，不能被二次转义")
    void escapesBackslashFirst() {
        // 输入 c:\x  →  c:\\x  （SQL 侧配合 ESCAPE '\' 还原成字面反斜杠）
        assertThat(LikePattern.of("c:\\x")).isEqualTo("%c:\\\\x%");
        // 输入 %\_  →  三个字符各自转义，顺序错了这里就会多出反斜杠
        assertThat(LikePattern.of("%\\_")).isEqualTo("%\\%\\\\\\_%");
    }

    @Test
    @DisplayName("普通中文两字词原样包上 %，不做多余处理")
    void plainKeywordUntouched() {
        assertThat(LikePattern.of("涨停")).isEqualTo("%涨停%");
        assertThat(LikePattern.of("")).isEqualTo("%%");
        assertThat(LikePattern.of(null)).isEqualTo("%%");
    }

    @Test
    @DisplayName("搜 100% 只命中真带百分号的标题，不命中 100元")
    void searchWithPercentIsPrecise() {
        TestDb.insert(jdbc, "a", "公司利润增长100%（公告）", "正文", 100);
        TestDb.insert(jdbc, "b", "公司利润增长100元", "正文", 101);

        assertThat(titles("100%")).containsExactly("公司利润增长100%（公告）");
        assertThat(titles("100")).as("不带通配符时两条都该命中").hasSize(2);
    }

    @Test
    @DisplayName("搜 a_b 不会命中 axb")
    void searchWithUnderscoreIsPrecise() {
        TestDb.insert(jdbc, "a", "代号 a_b 的产品", "正文", 100);
        TestDb.insert(jdbc, "b", "代号 axb 的产品", "正文", 101);

        assertThat(titles("a_b")).containsExactly("代号 a_b 的产品");
    }

    @Test
    @DisplayName("正文命中也算，且翻页游标不重复")
    void searchMatchesContentAndPages() {
        for (int i = 1; i <= 5; i++) {
            TestDb.insert(jdbc, "n" + i, "标题" + i, "正文都提到回购", 1000 + i);
        }
        List<News> first = store.search(LikePattern.of("回购"), Long.MAX_VALUE, 2);
        assertThat(first).hasSize(2);
        long cursor = first.get(first.size() - 1).id();
        List<News> second = store.search(LikePattern.of("回购"), cursor, 2);
        assertThat(second).hasSize(2);
        assertThat(second).extracting(News::id).doesNotContainAnyElementsOf(
                first.stream().map(News::id).toList());
        assertThat(store.countSearch(LikePattern.of("回购"))).isEqualTo(5);
    }
}
