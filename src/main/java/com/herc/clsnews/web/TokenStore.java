package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 短期令牌，落 SQLite（{@code auth_token} 表）。
 *
 * <p>为什么不再用「每次请求都带 Basic」：Basic 头里是 {@code user:pass} 的 Base64，
 * 等于把密码在每个请求上重复一遍。前端一旦存进 localStorage（小程序是 uni storage），
 * 任何一次 XSS 或设备共用都会把密码交出去；而密码是长期凭证，泄露之后只能改环境变量重启。
 * token 是短期、可作废、且不含密码的凭证 —— 这是换它的全部理由。
 *
 * <p><b>为什么从内存搬到表里</b>：原来的实现重启即全员掉线。这个站每次改配置、
 * 每次部署、每次容器滚动更新都会重启，用户那边的体感是「刷着刷着突然要重新登录」，
 * 而他手里什么都没做错。掉线本身还能忍，难受的是它没有规律、也没法解释。
 * 落库之后重启只是重启，会话照旧。代价是每次校验多一次主键查询 —— SQLite 本地文件，
 * 与新闻列表那些查询比可以忽略。
 *
 * <p><b>为什么不存令牌原文</b>：库文件会被备份、被 scp 到别的机器上排查问题。
 * 原文落库等于把可用的登录凭据一起备份了出去。这里存 SHA-256 哈希，校验时哈希后查，
 * 令牌本身是 32 字节随机数，反推不可行。数据库泄露时攻击者拿到的是不可用的哈希。
 *
 * <p><b>为什么不做 JWT</b>：JWT 的价值是「多实例之间不共享状态也能验签」，这个项目单实例、
 * 单文件 SQLite，反而需要「立刻作废某个 token」的能力 —— 那是无状态令牌的短板（只能等过期）。
 * 一张表加几十行代码换到确定的行为，比引一个库划算。哪天要多实例，这张表挪到 Redis 即可，
 * 语义不用动。
 *
 * <p>access 短、refresh 长，refresh 一次性使用（旋转）。旋转的意义：refresh 万一泄露，
 * 攻击者用一次之后真正的用户会在下一次刷新时被拒绝，暴露时间从 7 天缩到一次刷新。
 *
 * <p>但严格的「用一次就死」在多标签页上会打自己人：两个标签同时带着快过期的 access 请求、
 * 同时拿同一个 refresh 去换，只有一个能成，另一个被判为「重放」直接登出。
 * 所以旋转后旧 refresh 有 {@link #REFRESH_GRACE_MS} 的宽限窗口，窗口内的重复使用
 * 也照常发一对新的。窗口**不会再被延长**（见 {@link #refresh} 里的 MIN），所以最多 30 秒。
 */
@Component
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    /** 32 字节随机数，Base64Url 后 43 字符。够长到不需要再想暴力枚举的问题。 */
    private static final int TOKEN_BYTES = 32;

    /**
     * 旋转后旧 refresh 的宽限窗口。
     *
     * <p>这不是给攻击者的窗口 —— 攻击者本来就能用满整个 refresh TTL，
     * 这里只是让「两个标签同时刷新」不至于互相踢下线。30 秒够覆盖一条请求的往返，
     * 又短到不影响「泄露后尽快暴露」这个目标。
     */
    private static final long REFRESH_GRACE_MS = 30_000L;

    /** 一次签发的结果。字段名就是前端拿到的 JSON 字段名，别随手改。 */
    public record Tokens(String user, String role,
                         String accessToken, long accessExpiresAt,
                         String refreshToken, long refreshExpiresAt) {
        public long accessExpiresIn() {
            return Math.max(0, (accessExpiresAt - System.currentTimeMillis()) / 1000);
        }
    }

    /** 校验通过的调用者。 */
    public record Principal(String user, String role, long expiresAt) { }

    /** 表里的一行。{@code token_hash} 只用于定位，不参与业务。 */
    private record Row(String username, String role, String session, long expiresAt) { }

    private static final RowMapper<Row> MAP = (rs, i) -> new Row(
            rs.getString("username"), rs.getString("role"),
            rs.getString("session"), rs.getLong("expires_at"));

    private static final String SELECT_BY_HASH = """
            SELECT username, role, session, expires_at FROM auth_token
            WHERE token_hash = ? AND kind = ?
            """;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public TokenStore(JdbcTemplate jdbc, AppProps props) {
        this.jdbc = jdbc;
        this.accessTtl = props.getSecurity().getTokenAccessTtl();
        this.refreshTtl = props.getSecurity().getTokenRefreshTtl();
        log.info("令牌有效期：access {}，refresh {}（存 auth_token 表，重启不失效）", accessTtl, refreshTtl);
    }

    /** 签发一对新令牌（新的一次登录）。 */
    public Tokens issue(String user, String role) {
        purgeExpired();
        return issueInSession(user, role, newToken());
    }

    private Tokens issueInSession(String user, String role, String session) {
        long now = System.currentTimeMillis();
        String a = newToken();
        String r = newToken();
        long accessExp = now + accessTtl.toMillis();
        long refreshExp = now + refreshTtl.toMillis();

        jdbc.update("""
                INSERT INTO auth_token (token_hash, kind, username, role, session, expires_at, created_at)
                VALUES (?,?,?,?,?,?,?)
                """, hash(a), "access", user, role, session, accessExp, now);
        jdbc.update("""
                INSERT INTO auth_token (token_hash, kind, username, role, session, expires_at, created_at)
                VALUES (?,?,?,?,?,?,?)
                """, hash(r), "refresh", user, role, session, refreshExp, now);

        log.debug("签发令牌 user={} role={} access 到期={}", user, role, accessExp);
        return new Tokens(user, role, a, accessExp, r, refreshExp);
    }

    /**
     * 校验 access token。
     *
     * @return 有效则返回调用者；不存在、已过期或类型不符返回 {@code null}
     */
    public Principal resolveAccess(String token) {
        if (token == null || token.isEmpty()) return null;
        String h = hash(token);
        List<Row> rows = jdbc.query(SELECT_BY_HASH, MAP, h, "access");
        if (rows.isEmpty()) return null;

        Row e = rows.get(0);
        if (e.expiresAt() <= System.currentTimeMillis()) {
            // 惰性删除：过期行留着不会误放行（上面已经判了时间），但会让表只增不减。
            // 只在「确实存在且已过期」时才写一次库，伪造的随机串不会触发任何写操作。
            jdbc.update("DELETE FROM auth_token WHERE token_hash = ?", h);
            return null;
        }
        return new Principal(e.username(), e.role(), e.expiresAt());
    }

    /**
     * 用 refresh 换一对新令牌。
     *
     * <p>旋转做法：成功后把旧 refresh 的到期时间压到 {@code now + 宽限}，而不是立刻删除。
     * 压的时候用 {@code MIN(expires_at, ?)} —— 这样只有「剩余寿命比宽限更长」的行会被压，
     * 已经被压过的行再压不会延长。窗口有界是重点：否则每次重放都在续命，
     * 一个泄露的 refresh 能靠定时重放永久活着。
     *
     * <p>不回收旧 access：它最多再活 30 分钟，而浏览器里可能还有几个请求正带着它跑在路上，
     * 立刻作废只会让它们白跑一趟 401。要连 access 一起杀，那是登出。
     *
     * @return 新令牌；refresh 不存在或已过期返回 {@code null}
     */
    public Tokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) return null;
        String h = hash(refreshToken);
        long now = System.currentTimeMillis();

        List<Row> rows = jdbc.query(SELECT_BY_HASH, MAP, h, "refresh");
        if (rows.isEmpty()) {
            // 找不到 = 过期后被清掉了 / 这次登录已经登出 / 签发它的库不是这个库。
            // 排查「刷着刷着要重登」时，这条 WARN 说明服务端确实拒了一个真令牌，
            // 而不是前端压根没带上（后者日志里不会有任何记录）。
            log.warn("refresh 被拒：令牌不在库里（前 8 位 {}）", h.substring(0, 8));
            return null;
        }

        Row e = rows.get(0);
        if (e.expiresAt() <= now) {
            jdbc.update("DELETE FROM auth_token WHERE token_hash = ?", h);
            log.warn("refresh 被拒：令牌已过期 user={}", e.username());
            return null;
        }

        jdbc.update("UPDATE auth_token SET expires_at = MIN(expires_at, ?) WHERE token_hash = ?",
                now + REFRESH_GRACE_MS, h);

        log.debug("刷新令牌 user={}", e.username());
        // 沿用同一个 session：登出时才能把这一串换下来的 access 一起清掉
        Tokens t = issueInSession(e.username(), e.role(), e.session());
        purgeExpired();
        return t;
    }

    /**
     * 登出：按 session 作废这一串（可能已经轮换过几轮）令牌。
     *
     * <p>只删 refresh 是不够的：那样手里那个 access 还能再用满一个 TTL，
     * 用户点了「退出」却并没有退出。
     *
     * <p>refresh 可能已经被旋转过（手里是上一次刷新前的那个），行还在宽限窗口里，
     * 照样能查到 session —— 用户点退出时手里拿的是哪个 refresh 都能登出。
     *
     * @return 作废的条数（access + refresh），仅用于日志
     */
    public int revoke(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) return 0;
        List<Row> rows = jdbc.query(SELECT_BY_HASH, MAP, hash(refreshToken), "refresh");
        if (rows.isEmpty()) return 0;

        Row e = rows.get(0);
        int removed = jdbc.update("DELETE FROM auth_token WHERE session = ?", e.session());
        log.debug("登出 user={} 作废 {} 条", e.username(), removed);
        return removed;
    }

    /**
     * 清掉已过期的行。
     *
     * <p>没有用定时任务：签发和刷新都是低频操作，表很小（几个账号 × 几台设备），
     * 顺手删一次的成本可以忽略。定时任务反而多一个线程和一处需要关闭的资源。
     *
     * <p>不清理也不会误放行（所有查询都带 expires_at 判断），清理只是防止表无限增长。
     */
    private void purgeExpired() {
        int n = jdbc.update("DELETE FROM auth_token WHERE expires_at <= ?", System.currentTimeMillis());
        if (n > 0) log.debug("清理过期令牌 {} 条", n);
    }

    private String newToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        random.nextBytes(buf);
        return encoder.encodeToString(buf);
    }

    /** 令牌 → 落库键。原文不落库，见类注释。 */
    private static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走到这里说明运行环境被裁剪过
            throw new IllegalStateException("缺少 SHA-256", e);
        }
    }
}
