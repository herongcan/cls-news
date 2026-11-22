package com.herc.clsnews.store;

import com.herc.clsnews.web.UserAgent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 访问明细。
 *
 * <p>只由 {@code POST /api/visit/ping} 写入，而那个接口在 {@code /api/**} 后面，
 * 需要 Basic 凭证。所以扫描器即使能拿到免鉴权的 {@code index.html}，
 * 也写不进这张表 —— 这正是它比"统计 GET /"强的地方。
 */
@Repository
public class AccessLogStore {

    /** UA 截断长度。UA 本身没有长度上限，不截断等于让人往库里写任意大小的文本。 */
    private static final int UA_MAX = 512;
    private static final int PATH_MAX = 256;
    private static final int REFERER_MAX = 256;

    /** 明细限流的窗口：同一 IP 这段时间内只落一行。 */
    private static final long DEDUP_SECONDS = 60;

    /** 限流表容量上限。正常只有几十个 IP，设这个只是防止被刷爆内存。 */
    private static final int SEEN_MAX = 4096;

    private final JdbcTemplate jdbc;

    /** ip -> 上次落库时间。单实例部署足够；多实例时各自独立计数，会偏多。 */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public AccessLogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记一条访问明细。
     *
     * <p>同一 IP 60 秒内重复调用直接跳过：不加这个，有人连按 F5 二十次就是二十行，
     * "访问次数"又退化成刷新频率了。{@code visit} 表的 hits 仍然每次累加，
     * 那是聚合口径，两者不冲突。
     *
     * @return true = 已写入；false = 被限流跳过
     */
    public boolean record(String ip, String vid, String ua, String path, String referer) {
        String safeIp = ip == null ? "" : ip.trim();
        if (safeIp.isEmpty()) {
            return false;
        }
        long now = Instant.now().getEpochSecond();

        Long prev = lastSeen.get(safeIp);
        if (prev != null && now - prev < DEDUP_SECONDS) {
            return false;
        }
        if (lastSeen.size() > SEEN_MAX) {
            lastSeen.clear();
        }
        lastSeen.put(safeIp, now);

        UserAgent.Info info = UserAgent.parse(ua);
        jdbc.update("""
                INSERT INTO access_log (ts, day, ip, vid, ua, device, os, browser, path, referer)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                now,
                java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString(),
                safeIp,
                cut(cleanVid(vid), 64),
                cut(ua, UA_MAX),
                info.device(),
                info.os(),
                info.browser(),
                cut(path, PATH_MAX),
                cut(referer, REFERER_MAX));
        return true;
    }

    /**
     * 按 vid 去重的访客数（同一天）。
     *
     * <p>{@code COALESCE(NULLIF(vid,''), ip)} 是这段的关键：有 vid 用 vid，
     * 没有（无痕模式、清理过缓存、老客户端）退回按 IP 算。
     * 少了它，那些拿不到 vid 的访问会被算成同一个人（全归到空串），UV 反而偏低。
     */
    public long uvOnDay(String day) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT COALESCE(NULLIF(vid, ''), ip)) FROM access_log WHERE day = ?
                """, Long.class, day);
        return n == null ? 0 : n;
    }

    /** 设备分布，按次数倒序。给页面展示用，不含任何 IP。 */
    public List<Map<String, Object>> deviceStats(String day) {
        return jdbc.queryForList("""
                SELECT device, COUNT(*) AS n FROM access_log
                WHERE day = ? GROUP BY device ORDER BY n DESC
                """, day);
    }

    /** 24 小时分布，小时为空补 0。用来回答「大家什么时候在看」。 */
    public List<Map<String, Object>> hourHistogram(String day) {
        Map<Integer, Long> counts = new java.util.HashMap<>();
        jdbc.query("""
                SELECT CAST(strftime('%H', ts, 'unixepoch', 'localtime') AS INTEGER) AS h,
                       COUNT(*) AS n
                FROM access_log WHERE day = ? GROUP BY h
                """, (RowCallbackHandler) rs -> counts.put(rs.getInt("h"), rs.getLong("n")), day);

        List<Map<String, Object>> out = new java.util.ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            out.add(Map.of("hour", h, "n", counts.getOrDefault(h, 0L)));
        }
        return out;
    }

    /**
     * 一行访问明细。字段顺序刻意跟建表语句一致，读代码时不用来回对。
     *
     * <p>用 record 而不是 {@code Map<String,Object>}：Map 的 key 拼错只有运行时才知道，
     * 而这里少写一个字段编译就过不去。真正的输出格式化交给 Controller。
     */
    public record LogRow(long id, long ts, String ip, String vid, String device, String os,
                         String browser, String path, String referer, String ua) {
    }

    /**
     * 按条件倒序取明细，游标分页。
     *
     * <p>用 {@code id < before} 而不是 {@code ts < before}：同一秒内的多条记录
     * 用时间游标会漏——分页边界正好落在同一秒里，就有几行永远翻不到。
     * id 是自增主键，天然唯一且与写入顺序一致。
     *
     * @param day      自然日，必填（不做「全部日期」：没有 day 的查询要走全表扫）
     * @param ipPrefix IP 前缀（如 {@code 114.247.}），null/空表示不过滤
     * @param device   设备类型精确匹配，null/空表示不过滤
     * @param before   游标，0 表示第一页
     * @param limit    条数，调用方已夹紧
     */
    public List<LogRow> list(String day, String ipPrefix, String device, long before, int limit) {
        List<Object> args = new java.util.ArrayList<>();
        String where = whereClause(day, ipPrefix, device, before, args);
        args.add(limit);
        return jdbc.query("""
                SELECT id, ts, ip, vid, device, os, browser, path, referer, ua
                FROM access_log""" + where + " ORDER BY id DESC LIMIT ?",
                (rs, i) -> new LogRow(
                        rs.getLong("id"), rs.getLong("ts"), rs.getString("ip"), rs.getString("vid"),
                        rs.getString("device"), rs.getString("os"), rs.getString("browser"),
                        rs.getString("path"), rs.getString("referer"), rs.getString("ua")),
                args.toArray());
    }

    /** 同条件下的总行数（不含游标条件），用来算「还有多少」和「共几行」。 */
    public long countBy(String day, String ipPrefix, String device) {
        List<Object> args = new java.util.ArrayList<>();
        String where = whereClause(day, ipPrefix, device, 0, args);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM access_log" + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /**
     * 拼 WHERE 子句，条件值一律走绑定参数。
     *
     * <p>{@code ip} 用 LIKE 前缀匹配是刻意的：查「某个人」时手上往往只有
     * {@code 114.247.} 这样的网段，没有完整 IP。代价是 LIKE 用不上索引，
     * 但 day 条件已经把范围压到一天几十行，扫这点数据无所谓。
     */
    private static String whereClause(String day, String ipPrefix, String device,
                                      long before, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE day = ?");
        args.add(day);
        if (ipPrefix != null && !ipPrefix.isBlank()) {
            sb.append(" AND ip LIKE ? ESCAPE '\\'");
            args.add(escapeLike(ipPrefix.trim()) + "%");
        }
        if (device != null && !device.isBlank()) {
            sb.append(" AND device = ?");
            args.add(device.trim());
        }
        if (before > 0) {
            sb.append(" AND id < ?");
            args.add(before);
        }
        return sb.toString();
    }

    /**
     * 转义 LIKE 的通配符。
     *
     * <p>不转义的话，查 {@code 10.0.0.1} 里的 {@code .} 虽然没问题，
     * 但用户输入 {@code %} 就会变成「匹配全部」—— 一个查单人的条件静默返回全表。
     * 反斜杠必须第一个转，否则后面插入的转义符会被二次转义。
     */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 明细行数，给清理任务和排查用。 */
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM access_log", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 删除早于 cutoffTs 的明细。
     *
     * @return 删除行数
     */
    public int purgeBefore(long cutoffTs) {
        return jdbc.update("DELETE FROM access_log WHERE ts < ?", cutoffTs);
    }

    /**
     * vid 只允许十六进制和连字符。
     *
     * <p>和 {@code VisitStore.normalize} 同一原则：整条拒收，不做「滤掉非法字符再收下」。
     * 后者会把任意内容清洗成一个看起来合法的串，等于让它进了库。
     *
     * <p>public 是因为 {@code VisitStore} 的去重键也用它 —— 两张表必须认可同一批 vid，
     * 否则明细里能查到的人，聚合里会少一个。清洗只做一次，由 Controller 调用。
     */
    public static String cleanVid(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() < 8 || s.length() > 64) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F') || c == '-';
            if (!ok) {
                return null;
            }
        }
        return s;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
