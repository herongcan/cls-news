package com.herc.clsnews.store;

import com.herc.clsnews.model.News;
import com.herc.clsnews.model.RawItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 直接写 JDBC，不引入 ORM。
 * 这个项目只有一张表、六条 SQL，MyBatis 是负资产。
 */
@Repository
public class NewsStore {

    private static final RowMapper<News> MAP = NewsStore::map;

    private final JdbcTemplate jdbc;

    public NewsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 靠 item_id 唯一约束去重。返回 1 = 新插入，0 = 已存在。 */
    public int insertIfAbsent(RawItem it) {
        return jdbc.update("""
                INSERT OR IGNORE INTO news
                    (item_id, title, content, url, publish_time, level, stocks_json, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                it.itemId(), it.title(), it.content(), it.url(),
                it.publishTime(), it.level(), it.stocksJson(), Instant.now().getEpochSecond());
    }

    /** 首屏：按发布时间倒序。 */
    public List<News> latest(int limit) {
        return jdbc.query("SELECT * FROM news ORDER BY publish_time DESC, id DESC LIMIT ?", MAP, limit);
    }

    /** 增量：id 大于游标的都返回。按发布时间升序，前端逐个插到最前面即可保持「新的在上」。 */
    public List<News> since(long sinceId, int limit) {
        return jdbc.query(
                "SELECT * FROM news WHERE id > ? ORDER BY publish_time ASC, id ASC LIMIT ?", MAP, sinceId, limit);
    }

    /**
     * 向下翻历史：取 id 小于游标的一页，保持「新的在上」的倒序。
     *
     * <p>游标用 id 而不是 publish_time，因为入库前已按发布时间升序排过，
     * id 与时间严格同序；而 publish_time 可能重复（同一秒多条快讯），
     * 用它翻页会在边界漏条或重条。
     */
    public List<News> before(long beforeId, int limit) {
        return jdbc.query(
                "SELECT * FROM news WHERE id < ? ORDER BY publish_time DESC, id DESC LIMIT ?", MAP, beforeId, limit);
    }

    /** 游标之前还有多少条，用来判断要不要继续显示「加载更多」。 */
    public long countBefore(long beforeId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM news WHERE id < ?", Long.class, beforeId);
        return n == null ? 0 : n;
    }

    /** 全局最大 id，作为前端增量游标。用全局值而不是「本页最大值」，避免分页边界重复。 */
    public long maxId() {
        Long n = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM news", Long.class);
        return n == null ? 0 : n;
    }

    public Optional<News> findById(long id) {
        return jdbc.query("SELECT * FROM news WHERE id = ?", MAP, id).stream().findFirst();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM news", Long.class);
        return n == null ? 0 : n;
    }

    public long countByStatus(String status) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM news WHERE analysis_status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    /**
     * 原子认领：取一批 pending 并立刻改成 analyzing，同一事务内完成。
     *
     * 为什么不能「先 SELECT 再 UPDATE」：如果同时起了两个进程（实测踩过，
     * 两个实例连同一个 sqlite），两边会读到同一批 pending，各自调一次模型 ——
     * 同一条消息被扣两次钱。UPDATE...RETURNING 让「选出」和「占住」变成一步。
     */
    public List<News> claimPending(int limit) {
        return jdbc.query("""
                UPDATE news SET analysis_status = 'analyzing'
                WHERE id IN (SELECT id FROM news WHERE analysis_status = 'pending' ORDER BY id ASC LIMIT ?)
                RETURNING *
                """, MAP, limit);
    }

    /** 上次进程被杀/崩溃时留下的 analyzing 行，启动时打回 pending，否则这些条目永远卡住。 */
    public int resetStuck() {
        return jdbc.update("UPDATE news SET analysis_status='pending' WHERE analysis_status='analyzing'");
    }

    /**
     * 把「已认领但没来得及处理」的行打回 pending。
     *
     * <p>为什么必须有：{@link #claimPending} 一次把 N 条全置成 analysing，但 worker 可能
     * 因为连续失败提前退出，剩下那几条就永远停在 analysing —— 而 resetStuck 只在启动时跑一次，
     * 于是这些消息在正常运行期间再也不会被分析，也没有任何报错，是典型的数据静默丢失。
     */
    public int releaseClaims(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        // 只回滚仍然是 analyzing 的：万一某条已经被处理成 done，不能被这里覆盖成 pending
        return jdbc.update(
                "UPDATE news SET analysis_status='pending' WHERE analysis_status='analyzing' AND id IN (" + placeholders + ")",
                ids.toArray());
    }

    public void saveAnalysis(long id, String json, String model) {
        jdbc.update("UPDATE news SET analysis_status='done', analysis_json=?, analyzed_at=?, model=? WHERE id=?",
                json, Instant.now().getEpochSecond(), model, id);
    }

    public void markFailed(long id) {
        jdbc.update("UPDATE news SET analysis_status='failed' WHERE id = ?", id);
    }

    /** 把失败的全部打回 pending 重跑。调大 max_tokens、修好 key 之后用它，不用手工改库。 */
    public int requeueFailed() {
        return jdbc.update("UPDATE news SET analysis_status='pending' WHERE analysis_status='failed'");
    }

    private static News map(ResultSet rs, int rowNum) throws SQLException {
        long at = rs.getLong("analyzed_at");
        Long analyzedAt = rs.wasNull() ? null : at;
        return new News(
                rs.getLong("id"),
                rs.getString("item_id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("url"),
                rs.getLong("publish_time"),
                rs.getString("level"),
                rs.getString("stocks_json"),
                rs.getLong("created_at"),
                rs.getString("analysis_status"),
                rs.getString("analysis_json"),
                analyzedAt,
                rs.getString("model"));
    }
}
