package com.herc.clsnews.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * <p>为什么不在登录页直接把密码存起来反复用：见 {@link TokenStore} 的类注释。
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class AuthController {

    /** refresh / logout 的请求体。用 record 接，不引入额外依赖。 */
    public record RefreshRequest(String refreshToken) { }

    private final AccountStore accounts;
    private final TokenStore tokens;

    public AuthController(AccountStore accounts, TokenStore tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    /**
     * 用 Basic 头换一对令牌。
     *
     * <p>用头而不是请求体传密码：{@code curl -u} 直接可用，不需要先拼 JSON；
     * 也不会把密码写进任何会被日志记录的 body。
     */
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> token(
            @RequestHeader(value = "Authorization", required = false) String header) {

        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Map.of("error", "缺少 Basic 凭证"));
        }
        AccountStore.Account acct;
        try {
            byte[] raw = Base64.getDecoder().decode(header.substring(6).trim());
            acct = accounts.verifyBasic(raw);
        } catch (IllegalArgumentException e) {
            // Base64 都解不开，和密码错误等价处理，不额外透露是哪一步错
            acct = null;
        }
        if (acct == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Map.of("error", "账号或密码不对"));
        }

        TokenStore.Tokens t = tokens.issue(acct.user(), acct.role());
        return ResponseEntity.ok(body(t));
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
