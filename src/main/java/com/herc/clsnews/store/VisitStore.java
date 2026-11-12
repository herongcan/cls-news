package com.herc.clsnews.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 访客统计。去重维度是「IP + 自然日」，写在表主键上。
 *
 * <p>为什么不按请求计数：页面每 30 秒轮询一次，一个人开着不动一天能产生几千次请求，
 * 按请求数算出来的是「刷屏次数」，不是「来了几个人」。
 * 所以只统计页面打开（GET / 或 /index.html），并且同一个 IP 当天只算一个访客。
 *
 * <p>已知局限：同一出口 IP 下的多个人（公司 NAT、运营商大内网）会被算成一个。
 * 这是 IP 口径的天花板，不是实现缺陷；要做准必须有登录态或 cookie。
 */
@Repository
public class VisitStore {

    private static final int IP_MAX_LEN = 64;

    private final JdbcTemplate jdbc;

    public VisitStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记一次访问。同一 IP 同一天重复访问只累加 hits，不会新增访客。
     *
     * <p>用 upsert 而不是「先查再插」：两个人同时打开页面时，
     * 先查后插会撞主键冲突，把一个正常的访问变成 500。
     */
    public void record(String ip, String day) {
        String safe = normalize(ip);
        if (safe == null) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        jdbc.update("""
                INSERT INTO visit (ip, day, first_ts, last_ts, hits)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(ip, day) DO UPDATE SET
                    last_ts = excluded.last_ts,
                    hits    = visit.hits + 1
                """, safe, day, now, now);
    }

    /** 今天来过多少人（按 IP 去重）。 */
    public long countToday(String day) {
        // 等价于 COUNT(*)（主键已保证 ip+day 唯一），写成 DISTINCT 是为了让 SQL 自解释
        Long n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT ip) FROM visit WHERE day = ?", Long.class, day);
        return n == null ? 0 : n;
    }

    /** 累计来过多少人（跨天按 IP 去重）。 */
    public long countTotal() {
        Long n = jdbc.queryForObject("SELECT COUNT(DISTINCT ip) FROM visit", Long.class);
        return n == null ? 0 : n;
    }

    /** 今天页面一共被打开多少次。只用于排查「到底是没人来还是没人刷新」。 */
    public long hitsToday(String day) {
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(hits), 0) FROM visit WHERE day = ?", Long.class, day);
        return n == null ? 0 : n;
    }

    /** 最近几天的访客数，按日期倒序。给运维手动查用，页面不展示。 */
    public List<Map<String, Object>> recentDays(int limit) {
        int n = Math.max(1, Math.min(limit, 90));
        return jdbc.queryForList("""
                SELECT day, COUNT(DISTINCT ip) AS uv, SUM(hits) AS pv
                FROM visit GROUP BY day ORDER BY day DESC LIMIT ?
                """, n);
    }

    /**
     * 清洗 IP。
     *
     * <p>IP 可能来自 X-Forwarded-For，而这个头客户端可以随便伪造 ——
     * 不校验就落库，等于让人往你的表里写任意字符串（长度、编码都不可控）。
     *
     * <p>这里用「整条拒收」而不是「滤掉非法字符再收下」：
     * 后者会把 {@code <script>alert(1)</script>} 变成 {@code scriptalert1script}
     * 照样存进库，统计里就凭空多出一个不存在的访客。
     * 所以非法内容直接返回 null，这次请求不计入。
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > IP_MAX_LEN) {
            return null;
        }
        boolean hasSeparator = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == ':') {
                hasSeparator = true;
                continue;
            }
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '_' || c == '-';
            if (!ok) {
                return null;
            }
        }
        // 至少得有个 . 或 :，否则 "abc" 这种也会被当成一个访客
        return hasSeparator ? s : null;
    }
}
