package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用 Basic 换 token、刷新、登出。
 *
 * <p>这三个路径在 {@link BasicAuthFilter} 里被放行 —— 它们必须能不带有效凭证访问，
 * 否则「凭证过期了想换新凭证」这件事就没有入口。放行的前提是本类自己把校验做严：
 * {@code /token} 校验 Basic 原文，{@code /refresh} 校验 refresh 一次性令牌。
 *
 * <p>{@code /token} 是**全站唯一校验密码的地方**：{@link BasicAuthFilter} 已经不再接受
 * Basic 访问任何业务接口，所以暴力破解的入口只有这一个，限流也只加在这一个上
 * （见 {@link LoginThrottle}）。
 *
 * <p>为什么不在登录页直接把密码存起来反复用：见 {@link TokenStore} 的类注释。
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** refresh / logout 的请求体。用 record 接，不引入额外依赖。 */
    public record RefreshRequest(String refreshToken) { }

    private final AccountStore accounts;
    private final TokenStore tokens;
    private final LoginThrottle throttle;
    private final AppProps props;

    public AuthController(AccountStore accounts, TokenStore tokens, LoginThrottle throttle, AppProps props) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.throttle = throttle;
        this.props = props;
    }

    /**
     * 用 Basic 头换一对令牌。
     *
     * <p>用头而不是请求体传密码：{@code curl -u} 直接可用，不需要先拼 JSON；
     * 也不会把密码写进任何会被日志记录的 body。
     *
     * <p>失败路径有三条，前两条都要计数：
     * <ol>
     *   <li>被锁 → 429 + {@code Retry-After}，**完全不碰密码**（锁定期连正确的密码也不该放行，
     *       否则锁定形同虚设）</li>
     *   <li>凭证不对 → 先按失败次数等一小会儿（指数退避），再回 401</li>
     *   <li>凭证正确 → 清零计数并签发</li>
     * </ol>
     *
     * <p>用户名在密码校验**之前**就从头里解出来，只用于选计数键。它不可信，
     * 但也不需要可信：拿它当键只是为了让「同一个 IP 试同一个账号」能被数到。
     */
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> token(
            @RequestHeader(value = "Authorization", required = false) String header,
            HttpServletRequest req) {

        String ip = ClientIp.of(req, props.getSecurity().isTrustForwardedHeaders());
        String user = basicUser(header);

        LoginThrottle.Decision d = throttle.check(ip, user);
        if (!d.allowed()) {
            // 日志留一条：连续 429 出现在运维视角里就是「有人在试口令」
            log.warn("换令牌被限流：ip={} user={} 还需等待 {}s", ip, user, d.retryAfterSec());
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(d.retryAfterSec()))
                    .body(Map.of("error", "登录尝试过于频繁，请 " + humanWait(d.retryAfterSec()) + "后再试"));
        }

        AccountStore.Account acct = verify(header);
        if (acct == null) {
            long delay = throttle.recordFailure(ip, user);
            pause(delay);
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Map.of("error", "账号或密码不对"));
        }

        throttle.recordSuccess(ip, user);
        return ResponseEntity.ok(body(tokens.issue(acct.user(), acct.role())));
    }

    /**
     * 把 {@code Retry-After} 的秒数说成人话。
     *
     * <p>「请 900 秒后再试」没人看得懂是多久；「15 分钟」一眼就明白该等还是该找管理员。
     */
    private static String humanWait(long sec) {
        if (sec < 60) return sec + " 秒";
        long min = (sec + 59) / 60;
        if (min < 60) return min + " 分钟";
        return (min + 59) / 60 + " 小时";
    }

    /**
     * 递增延迟。用 {@link Thread#sleep} 而不是异步：这个端点的正常流量是「登录一下」，
     * 一天几十次，占住一个 Tomcat 线程 2 秒完全不心疼；换来的是实现足够简单，
     * 不会因为异步调度出问题反而放行。
     */
    private static void pause(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 被中断（容器关闭）就不要再拖，把标志位还回去
            Thread.currentThread().interrupt();
        }
    }

    /** 校验 Basic 凭证。任何解析异常都当「不对」，不区分是哪一步错。 */
    private AccountStore.Account verify(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(header.substring(6).trim());
            return accounts.verifyBasic(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 从 Basic 头里取出用户名，取不到就返回空串。
     *
     * <p>只切第一个冒号：密码里可以有冒号，用户名不能。
     * 用 ISO-8859-1 解而不是 UTF-8：这一步只为了得到计数键，用户名按约定是 ASCII，
     * 解错了也不影响安全（键错只会让计数分成两条，不会让锁失效）。
     */
    private static String basicUser(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return "";
        }
        try {
            byte[] raw = Base64.getDecoder().decode(header.substring(6).trim());
            int colon = -1;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == ':') { colon = i; break; }
            }
            if (colon <= 0) return "";
            return new String(raw, 0, colon, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * 用 refresh 换新的一对。旧的 refresh 立刻失效（旋转）。
     *
     * <p>返回 401 时前端必须清空本地凭证并回到登录层 —— 这说明 7 天也过了。
     * 所以文案要说人话：「登录已过期，请重新登录」，而不是 {@code invalid_grant}。
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody(required = false) RefreshRequest req) {
        TokenStore.Tokens t = tokens.refresh(req == null ? null : req.refreshToken());
        if (t == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Map.of("error", "登录已过期，请重新登录"));
        }
        return ResponseEntity.ok(body(t));
    }

    /**
     * 登出。按 refresh 找到这次登录的 session，把 access 与 refresh 一起作废。
     *
     * <p>只作废 refresh 是不够的：那个 access 还能再用满一个 TTL（默认 30 分钟），
     * 表现就是「点了退出，但复制出来的请求还能通」。
     *
     * <p>永远返回 200：前端登出时不该因为「令牌本来就过期了」而卡住，
     * 它要做的事只是清本地状态。
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody(required = false) RefreshRequest req) {
        tokens.revoke(req == null ? null : req.refreshToken());
        return Map.of("ok", true);
    }

    private Map<String, Object> body(TokenStore.Tokens t) {
        // 用 LinkedHashMap 而不是 Map.of：字段顺序固定，curl 输出读起来不用来回扫
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", t.user());
        m.put("role", t.role());
        m.put("accessToken", t.accessToken());
        m.put("accessExpiresAt", t.accessExpiresAt());
        m.put("expiresIn", t.accessExpiresIn());
        m.put("refreshToken", t.refreshToken());
        m.put("refreshExpiresAt", t.refreshExpiresAt());
        return m;
    }
}
