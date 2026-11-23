package com.herc.clsnews.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;

/**
 * 测试用的临时库。
 *
 * <p>用**文件**而不是 {@code mode=memory}：内存库靠「至少有一个连接还开着」续命，
 * 而 JDBC 模板每次查询都会新开连接、用完就关 —— 中间那一刻库就没了，
 * 报错是「no such table: news」，看起来像建表逻辑坏了，其实是库丢了。
 * 文件库没有这个坑，也能直接支撑并发测试（两个线程各自拿连接）。
 *
 * <p>建表直接执行生产用的 {@code schema.sql}，不手抄一份：
 * 抄一份的话 schema 改了测试还是绿的，那这个测试就没意义了。
 */
public final class TestDb {

    private TestDb() {
    }

    /** 建一个空的临时库，表结构与生产一致。 */
    public static JdbcTemplate create(Path dir) throws Exception {
        return create(dir, "news.db");
    }

    /** @param name 文件名，同一个测试里需要两个独立库时用 */
    public static JdbcTemplate create(Path dir, String name) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve(name));
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("schema.sql"));
        }
        return new JdbcTemplate(ds);
    }

    /** 插一条 pending 的新闻，返回自增 id。 */
    public static long insert(JdbcTemplate jdbc, String itemId, long publishTime) {
        return insert(jdbc, itemId, "标题 " + itemId, "正文 " + itemId, publishTime);
    }

    public static long insert(JdbcTemplate jdbc, String itemId, String title, String content, long publishTime) {
        jdbc.update("""
                INSERT INTO news (item_id, title, content, url, publish_time, level, created_at)
                VALUES (?,?,?,?,?,?,?)
                """, itemId, title, content, "https://www.cls.cn/detail/" + itemId, publishTime, "B",
                publishTime);
        Long id = jdbc.queryForObject("SELECT id FROM news WHERE item_id = ?", Long.class, itemId);
        return id == null ? 0 : id;
    }

    /** 直接改状态，用来摆出 claim 的初始局面。 */
    public static void setStatus(JdbcTemplate jdbc, long id, String status) {
        jdbc.update("UPDATE news SET analysis_status = ? WHERE id = ?", status, id);
    }

    public static String status(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT analysis_status FROM news WHERE id = ?", String.class, id);
    }
}
