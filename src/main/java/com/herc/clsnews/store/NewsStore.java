package com.herc.clsnews.store;

import com.herc.clsnews.model.News;
import com.herc.clsnews.model.RawItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 直接写 JDBC，不引入 ORM。
 * 这个项目只有一张表、六条 SQL，MyBatis 是负资产。
 */
@Repository
public class NewsStore {

    private static final RowMapper<News> MAP = NewsStore::map;

    private static final Logger log = LoggerFactory.getLogger(NewsStore.class);

    private final JdbcTemplate jdbc;

    public NewsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        addActorColumnIfMissing();
    }

    /**
     * 给老库的 {@code analysis_call} 补 {@code actor} 列。
     *
     * <p>schema.sql 用的是 {@code CREATE TABLE IF NOT EXISTS}，对已经存在的表一个字都不会改；
     * 而 SQLite 的 ALTER TABLE 没有 {@code ADD COLUMN IF NOT EXISTS}，重复执行会直接抛错。
     * 所以只能先问 PRAGMA 再决定要不要加 —— 新装的库走 schema.sql 时就已经有这列，查完就跳过。
     *
     * <p>老数据留 NULL：分不清是谁触发的，只能按全站口径统计。
     */
    private void addActorColumnIfMissing() {
        List<Map<String, Object>> cols = jdbc.queryForList("PRAGMA table_info(analysis_call)");
        boolean has = cols.stream().anyMatch(c -> "actor".equals(String.valueOf(c.get("name"))));
        if (!has) {
            jdbc.execute("ALTER TABLE analysis_call ADD COLUMN actor TEXT");
            log.info("analysis_call 已补上 actor 列，历史记录按全站口径统计");
        }
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

    /**
     * 增量：id 大于游标的都返回，按 <b>id</b> 升序。
     *
     * <p>这里的 {@code ORDER BY} 不能写 {@code publish_time}，即使它看起来更「自然」。
     * 游标的过滤键是 id，排序键就必须也是 id —— 两者不一致时，一页装不下的那次
     * （停机、离线、批量补数据之后积压超过 limit）会让「本页最后一条」和
     * 「本页最大 id」不是同一行：回填进来的老消息 id 大、publish_time 小，
     * 排在最前面把一页占满，游标落在它身上，中间那批就被永久跳过。
     * 这个 bug 曾经真实存在，症状是「锁屏两分钟回来少了几条」。
     *
     * <p>代价是增量窗口内按「入库顺序」而不是「发布时间」呈现，回填的旧消息
     * 会短暂跳到最上面一次；下一次整屏刷新就恢复按发布时间排。
     * 宁可顺序难看一点，也不能丢条目。
     */
    public List<News> since(long sinceId, int limit) {
        return jdbc.query(
                "SELECT * FROM news WHERE id > ? ORDER BY id ASC LIMIT ?", MAP, sinceId, limit);
    }

    /**
     * 游标之后是否还有行。给增量分页判断「要不要继续追」。
     *
     * <p>不用 {@code count(*)}：只需要知道「有没有」，命中一条就能停。
     */
    public boolean hasAfter(long id) {
        return !jdbc.queryForList("SELECT 1 FROM news WHERE id > ? LIMIT 1", id).isEmpty();
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

    /**
     * 关键词搜索：标题或正文命中即算，排序与游标沿用 {@link #before} 那一套。
     *
     * <p><b>为什么用 LIKE，而不给 title 建索引</b>——这问题值得写清楚，因为直觉通常是错的：
     * <ul>
     *   <li>{@code LIKE '%词%'} <b>用不上 B-tree 索引</b>。索引按前缀有序存放，
     *       而这模式第一个字符就是通配符，意味着「任意位置都可能命中」，
     *       有序性一点都用不到，优化器只能全表扫。建了索引也不会被选中，
     *       白占空间、拖慢插入。能走索引的只有 `LIKE '词%'`（前缀匹配）。</li>
     *   <li>FTS5 的 trigram 分词要求查询词 <b>至少 3 个字符</b>，而中文关键词
     *       大多是两个字（回购、涨停、降息、减持），装上去反而搜不到；
     *       unicode61 又把连续汉字当一个 token，同样做不了词级匹配。</li>
     *   <li>实测：全表扫 441 行约 3ms，10 万行（每行 100 字正文）约 35ms。
     *       这个量级不值得引入任何额外结构。</li>
     * </ul>
     * 真到百万行，再考虑 FTS5 自写 tokenizer 或独立搜索引擎。
     *
     * @param pattern 已转义并带首尾 % 的 LIKE 模式，由调用方构造（见 NewsController#likePattern）
     * @param beforeId 游标上界（不含），传 {@code Long.MAX_VALUE} 表示从头开始
     */
    public List<News> search(String pattern, long beforeId, int limit) {
        return jdbc.query("""
                SELECT * FROM news
                 WHERE (title LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\')
                   AND id < ?
                 ORDER BY publish_time DESC, id DESC
                 LIMIT ?
                """, MAP, pattern, pattern, beforeId, limit);
    }

    /** 命中总数，前端用来显示「找到 N 条」。 */
    public long countSearch(String pattern) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM news
                 WHERE title LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\'
                """, Long.class, pattern, pattern);
        return n == null ? 0 : n;
    }

    /**
     * 全局最大 id。
     *
     * <p><b>不要拿它当增量游标。</b>它表示「库里最新的一条」，不代表「已经交付给用户的一条」；
     * 拿它推进游标会在积压超过一页时把没交付的条目直接跳过。游标请用响应里的
     * {@code nextSince}（= 本次真正返回的最后一条 id）。
     *
     * <p>现在只剩两个用途：接口里的兼容字段、以及给测试/脚本判断进度。
     */
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

    /**
     * 单条原子认领。手动分析用，等价于 {@link #claimPending} 的「抢一条」版本。
     *
     * <p>为什么条件写 {@code analysis_status <> 'analyzing'} 而不是 {@code = 'pending'}：
     * 界面只给「没结论」的条目按钮，但运维带 force=true 手工重算时已 done 的行也必须放行；
     * 唯一要挡的是「已经有人在算」，否则两个人同时点同一条就是两次扣费。
     *
     * <p>返回空 Optional 有两种可能：id 不存在、或已被别的请求抢走 ——
     * 调用方统一按「正在分析中」处理即可。
     */
    public Optional<News> claimOne(long id) {
        return jdbc.query("""
                UPDATE news SET analysis_status = 'analyzing'
                WHERE id = ? AND analysis_status <> 'analyzing'
                RETURNING *
                """, MAP, id).stream().findFirst();
    }

    /**
     * 记一次真实的大模型调用。日配额按这个数算，因为它才是钱。
     *
     * @param actor 谁触发的（rw / guest / ro / auto）。按角色记而不是按用户名：
     *              游客额度是一笔固定预算池，多开一个账号不该翻倍。
     */
    public void recordCall(long newsId, String actor) {
        jdbc.update("INSERT INTO analysis_call (ts, news_id, actor) VALUES (?,?,?)",
                Instant.now().getEpochSecond(), newsId, actor);
    }

    /**
     * 今天已经调用过多少次模型。跨重启准确，多实例共享同一份计数。
     *
     * @param actor null = 全站总次数（主账号看到的数字）；非 null = 只数这个角色花的
     */
    public long countCallsSince(long epochSecond, String actor) {
        Long n = actor == null
                ? jdbc.queryForObject(
                        "SELECT COUNT(*) FROM analysis_call WHERE ts >= ?", Long.class, epochSecond)
                : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM analysis_call WHERE ts >= ? AND actor = ?",
                        Long.class, epochSecond, actor);
        return n == null ? 0 : n;
    }

    public long countCallsSince(long epochSecond) {
        return countCallsSince(epochSecond, null);
    }

    /** 清掉老的调用记录，避免这张表无限长。启动时跑一次就够。 */
    public int pruneCalls(long beforeEpochSecond) {
        return jdbc.update("DELETE FROM analysis_call WHERE ts < ?", beforeEpochSecond);
    }

    /** 按 id 批量查，给「同步已展示条目的分析状态」用。顺序不保证，调用方按 id 自己索引。 */
    public List<News> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("SELECT * FROM news WHERE id IN (" + placeholders + ")", MAP, ids.toArray());
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
