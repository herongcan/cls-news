package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

/**
 * HTTP Basic 认证。只在 {@code app.security.enabled=true} 时生效。
 *
 * <p>为什么不用 spring-boot-starter-security：那套会自带默认登录页、CSRF、会话等一整串行为，
 * 对一个只有一页静态 HTML 的私用工具来说，需要关掉的东西比要打开的多。这里 40 行 Filter
 * 已经把这件事说完，且行为完全可预期。
 *
 * <p>为什么密码为空时拒绝启动而不是放行：最危险的状态不是「没开鉴权」，
 * 而是「以为开了鉴权、实际上密码是空字符串」。宁可起不来。
 *
 * <p>注意：Basic 认证的密码是 Base64 明文，不是加密。**必须套 HTTPS**，
 * 否则等同明文密码在公网上裸奔。
 */
@Component
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BasicAuthFilter.class);
    private static final String REALM = "cls-news";

    /**
     * 页面本身必须放行。
     *
     * <p>移动端浏览器（微信、QQ、UC 的内置内核，以及不少 Android WebView）收到
     * 401 + {@code WWW-Authenticate} 之后**不会弹出原生输入框**，而是直接把 401
     * 渲染成一张白底页面 —— 电脑上一切正常，同事用手机点开只看到「需要登录」三个字。
     *
     * <p>所以改成由页面自己收凭证：HTML 先加载出来，登录框做在页面里，
     * 输入后用 Base64 塞进 {@code Authorization} 头去打 /api/**。
     *
     * <p>代价是 index.html 本身免认证就能拿到 —— 它只是个空壳，所有数据都在
     * /api/** 后面，泄露的只有标题和样式表。
     */
    private static final Set<String> PAGE_PATHS = Set.of("/", "/index.html");

    private final byte[] expected;
    private final Set<String> permitPaths;

    public BasicAuthFilter(AppProps props) {
        AppProps.Security cfg = props.getSecurity();
        String user = cfg.getUsername() == null ? "" : cfg.getUsername().trim();
        String pass = cfg.getPassword() == null ? "" : cfg.getPassword();

        if (user.isBlank() || pass.isBlank()) {
            throw new IllegalStateException("""
                    已开启 app.security.enabled=true，但用户名或密码为空。
                    请设置环境变量 APP_USER / APP_PASSWORD（或 app.security.username/password）。
                    这里故意让启动失败：空密码的「已开启鉴权」比明说没开更危险。""");
        }

        this.expected = (user + ":" + pass).getBytes(StandardCharsets.UTF_8);
        this.permitPaths = Set.of(cfg.getPermitPaths() == null ? new String[]{"/healthz"} : cfg.getPermitPaths());
        log.info("已开启访问控制：用户 {}，放行路径 {}", user, permitPaths);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        String uri = req.getRequestURI();

        // 白名单必须用「完全相等」而不是 startsWith。实测过：写成 startsWith 时，
        // /healthz/../api/status 会因为前缀是 /healthz 而被放行，然后交给下游做路径规范化——
        // 能不能挡住完全取决于 Tomcat 的实现，等于把安全交给别人的默认值。
        // 另外显式拒绝带 .. 或 // 的请求：规范化时机在不同容器/版本上不一致，不赌。
        if ((permitPaths.contains(uri) || PAGE_PATHS.contains(uri)) && !looksLikeTraversal(uri)) {
            chain.doFilter(req, resp);
            return;
        }

        String header = req.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                byte[] got = Base64.getDecoder().decode(header.substring(6).trim());
                // MessageDigest.isEqual 是常量时间比较，避免按字节短路泄露密码长度与前缀
                if (MessageDigest.isEqual(got, expected)) {
                    chain.doFilter(req, resp);
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Base64 都解不开，直接当认证失败
            }
        }

        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // 只给「非 API」请求发 challenge。
        // 加了这个头，部分浏览器（Safari 尤其明显）会在 fetch 拿到 401 之后
        // 额外弹一个原生登录框盖在自己页面上，和页面内的登录层打架、输完还关不掉。
        // 页面内的登录框不需要它；用 curl 调 /api/** 也不靠它。
        if (!uri.startsWith("/api/")) {
            resp.setHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
        }
        resp.setContentType("text/plain;charset=UTF-8");
        resp.getWriter().write("需要登录");
    }

    /** 白名单路径里出现穿越/重复斜杠一律不放行，交给鉴权流程处理。 */
    private boolean looksLikeTraversal(String uri) {
        return uri.contains("..") || uri.contains("//") || uri.contains(";");
    }
}
