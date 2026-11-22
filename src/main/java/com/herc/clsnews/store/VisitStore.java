package com.herc.clsnews.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 访客统计。去重维度是「设备标识 + 自然日」，写在 {@code visitor} 表的主键上。
 *
 * <p>为什么不按请求计数：页面每 30 秒轮询一次，一个人开着不动一天能产生几千次请求，
 * 按请求数算出来的是「刷屏次数」，不是「来了几个人」。
 * 所以只统计页面打开（前端拿到凭证后 ping 一次），同一个人当天只算一个访客。
 *
 * <p>为什么去重键从 IP 换成了 vid：公司出口 IP、运营商大内网后面可能有十个人，
 * 只按 IP 算他们是一个访客，数字长期偏低好几倍。vid 是前端生成后存在本地的随机串，
 * 粒度到设备，才接近「人」。拿不到 vid 时（无痕模式、清过缓存）仍退回按 IP。
 *
 * <p>旧的 {@code visit} 表已停写，只保留历史数据；启动后会把它的行按 {@code ip:}
 * 前缀搬进 {@code visitor}，所以老数字不会凭空消失。
 */
@Repository
public class VisitStore {

    private static final Logger log = LoggerFactory.getLogger(VisitStore.class);

    private static final int IP_MAX_LEN = 64;

    /** 去重键前缀。前缀是为了让「vid 恰好长得像 IP」这种巧合不会撞到一起。 */
    private static final String KEY_VID = "v:";
    private static final String KEY_IP = "ip:";

    private final JdbcTemplate jdbc;

    public VisitStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 把旧的 visit 表搬进 visitor（只搬今天以前的）。
     *
     * <p>挂在 {@link ApplicationReadyEvent} 上而不是构造函数里：构造时 schema.sql
     * 不一定已经跑完，建表语句还没执行就 INSERT 会让启动直接失败。这里晚一点但确定，
     * 而且失败也不该拦住启动 —— 统计挂掉不该让整个站起不来。
     *
     * <p>{@code INSERT OR IGNORE} 让它幂等：每次重启都跑一遍，已搬过的行（主键相同）
     * 直接被忽略，不需要另外记「迁移到哪了」。
     *
     * <p>只搬今天以前的：今天的访问新代码会重新按 vid 记一遍，搬过来会和新数据重复计。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyVisit() {
        try {
            String today = LocalDate.now(ZoneId.systemDefault()).toString();
            int n = jdbc.update("""
                    INSERT OR IGNORE INTO visitor (k, day, ip, vid, first_ts, last_ts, hits)
                    SELECT 'ip:' || ip, day, ip, NULL, first_ts, last_ts, hits
                    FROM visit WHERE day < ?
                    """, today);
            if (n > 0) {
                log.info("访客表迁移：{} 行旧 IP 口径数据并入 visitor", n);
            }
        } catch (Exception e) {
            log.warn("访客表迁移失败（不影响启动，累计从今天重新算）：{}", e.getMessage());
        }
    }

    /**
     * 记一次访问。同一个键同一天重复访问只累加 hits，不会新增访客。
     *
     * <p>用 upsert 而不是「先查再插」：两个人同时打开页面时，
     * 先查后插会撞主键冲突，把一个正常的访问变成 500。
     *
     * @param vid 设备标识，可为 null（无痕模式 / 存储被禁用），此时退回按 IP 去重
     */
    public void record(String ip, String vid, String day) {
        String safeIp = normalize(ip);
        if (safeIp == null) {
            return;
        }
        // vid 已在 Controller 里清洗过（只允许十六进制与连字符）。这里再判一次空：
        // 否则 k 会变成裸的 "v:"，所有拿不到 vid 的人会被并成同一个访客
        String key = (vid != null && !vid.isEmpty()) ? KEY_VID + vid : KEY_IP + safeIp;
        long now = Instant.now().getEpochSecond();
        jdbc.update("""
                INSERT INTO visitor (k, day, ip, vid, first_ts, last_ts, hits)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(k, day) DO UPDATE SET
                    last_ts = excluded.last_ts,
                    ip      = excluded.ip,
                    hits    = visitor.hits + 1
                """, key, day, safeIp, vid, now, now);
    }

    /** 今天来过多少个人（按设备标识去重）。 */
    public long countToday(String day) {
        // 主键已保证 k+day 唯一，COUNT(*) 就等于「不同设备数」；
        // 写成 DISTINCT 是为了让 SQL 自解释，也跟旧实现保持同一副长相
        Long n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT k) FROM visitor WHERE day = ?", Long.class, day);
        return n == null ? 0 : n;
    }

    /** 累计来过多少个人（跨天按设备标识去重）。 */
    public long countTotal() {
        Long n = jdbc.queryForObject("SELECT COUNT(DISTINCT k) FROM visitor", Long.class);
        return n == null ? 0 : n;
    }

    /** 今天页面一共被打开多少次。只用于排查「到底是没人来还是没人刷新」。 */
    public long hitsToday(String day) {
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(hits), 0) FROM visitor WHERE day = ?", Long.class, day);
        return n == null ? 0 : n;
    }

    /** 最近几天的访客数，按日期倒序。给运维手动查用，页面不展示。 */
    public List<Map<String, Object>> recentDays(int limit) {
        int n = Math.max(1, Math.min(limit, 90));
        return jdbc.queryForList("""
                SELECT day, COUNT(DISTINCT k) AS uv, SUM(hits) AS pv
                FROM visitor GROUP BY day ORDER BY day DESC LIMIT ?
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
