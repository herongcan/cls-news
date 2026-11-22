package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期令牌。存在内存里，进程重启即全部失效。
 *
 * <p>为什么不再用「每次请求都带 Basic」：Basic 头里是 {@code user:pass} 的 Base64，
 * 等于把密码在每个请求上重复一遍。前端一旦存进 localStorage（小程序是 uni storage），
 * 任何一次 XSS 或设备共用都会把密码交出去；而密码是长期凭证，泄露之后只能改环境变量重启。
 * token 是短期、可作废、且不含密码的凭证 —— 这是换它的全部理由。
 *
 * <p>为什么不做 JWT：JWT 的价值是「多实例之间不共享状态也能验签」，这个项目单实例、
 * 单文件 SQLite、随时可以重启，反而需要「立刻作废某个 token」的能力 —— 那正是无状态令牌
 * 的短板（只能等过期）。内存表用几十行换到了确定的行为，比引一个库划算。
 *
 * <p>代价写在明处：容器重启后所有前端会同时掉线，需要重新登录一次。
 * 对一个一天重启不到一次的私用工具来说，这个代价可以接受；如果哪天要多实例，
 * 这张表就得挪到 Redis，那时再换。
 *
 * <p>access 短、refresh 长，且 refresh 一次性使用（{@link #refresh} 会作废旧的那一对）。
 * 旋转的意义：refresh 万一泄露，攻击者用一次之后真正的用户会被强制重新登录，
 * 暴露时间从「7 天」缩到「下一次刷新」。前端并发请求同时触发刷新会互相打脸，
 * 所以 {@code http.ts} 那边做了「同一时刻只发一次刷新」的单飞处理。
 */
@Component
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    /** 32 字节随机数，Base64Url 后 43 字符。够长到不需要再想暴力枚举的问题。 */
    private static final int TOKEN_BYTES = 32;

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

    /**
     * 一条令牌记录。
     *
     * <p>{@code session} 是「同一次登录」的标识：access 和 refresh 配对签出时共用同一个值，
     * 刷新时保持不变，登出时按它一次性摘掉这一对。
     *
     * <p>为什么要有它：登出只删 refresh 的话，手里那个 access 还能再用满 30 分钟 ——
     * 用户点了「退出」却并没有退出。按 session 删就不会；也不能按 user 删，
     * 那样同一账号在另一台设备上会被顺带踢掉。
     */
    private record Entry(String user, String role, String session, long expiresAt) { }

    private final Map<String, Entry> access = new ConcurrentHashMap<>();
    private final Map<String, Entry> refresh = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public TokenStore(AppProps props) {
        this.accessTtl = props.getSecurity().getTokenAccessTtl();
        this.refreshTtl = props.getSecurity().getTokenRefreshTtl();
        log.info("令牌有效期：access {}，refresh {}（存内存，重启后失效）", accessTtl, refreshTtl);
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
        access.put(a, new Entry(user, role, session, accessExp));
        refresh.put(r, new Entry(user, role, session, refreshExp));
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
        Entry e = access.get(token);
        if (e == null) return null;
        if (e.expiresAt() <= System.currentTimeMillis()) {
            // 惰性删除：过期项留在表里不会造成误放行（下面已经判了时间），
            // 但会让表只增不减，所以顺手摘掉。
            access.remove(token);
            return null;
        }
        return new Principal(e.user(), e.role(), e.expiresAt());
    }

    /**
     * 用 refresh 换一对新令牌，旧的 refresh 立刻作废（旋转）。
     *
     * <p>不回收旧 access：它最多再活 30 分钟，而浏览器里可能还有几个请求正带着它跑在路上，
     * 立刻作废只会让它们白跑一趟 401。做法与 OAuth2 对 refresh 旋转的定义一致 ——
     * 旋转管的是 refresh，access 由自己的 TTL 收尾。要连 access 一起杀，那是登出。
     *
     * @return 新令牌；refresh 无效或过期返回 {@code null}
     */
    public Tokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) return null;
        Entry e = refresh.get(refreshToken);
        if (e == null) return null;
        if (e.expiresAt() <= System.currentTimeMillis()) {
            refresh.remove(refreshToken);
            return null;
        }
        // 先摘旧的再发新的：两个线程同时拿同一个 refresh 来换，只有一个能成功。
        // 这是有意的 —— 否则一次性使用就失去意义了。
        if (refresh.remove(refreshToken) == null) {
            return null;
        }
        log.debug("刷新令牌 user={}", e.user());
        // 沿用同一个 session：登出时才能把这一串换下来的 access 一起清掉
        return issueInSession(e.user(), e.role(), e.session());
    }

    /**
     * 登出：按 session 作废这一对（可能已经轮换过几轮）令牌。
     *
     * <p>只删 refresh 是不够的：那样手里那个 access 还能再用满一个 TTL，
     * 用户点了「退出」却并没有退出。
     *
     * @return 作废的条数（access + refresh），仅用于日志
     */
    public int revoke(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) return 0;
        Entry e = refresh.remove(refreshToken);
        if (e == null) return 0;
        String session = e.session();
        int before = access.size() + refresh.size();
        access.values().removeIf(x -> session.equals(x.session()));
        refresh.values().removeIf(x -> session.equals(x.session()));
        int removed = before - (access.size() + refresh.size());
        log.debug("登出 user={} 作废 {} 条", e.user(), removed);
        return removed;
    }

    /**
     * 每次签发时清一遍过期项。
     *
     * <p>没有用定时任务：签发频率远低于校验频率，且表很小（几个账号 × 几台设备），
     * 顺手遍历一次的成本可以忽略。定时任务反而多一个线程和一处需要关闭的资源。
     */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        access.values().removeIf(e -> e.expiresAt() <= now);
        refresh.values().removeIf(e -> e.expiresAt() <= now);
    }

    private String newToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        random.nextBytes(buf);
        return encoder.encodeToString(buf);
    }
}
