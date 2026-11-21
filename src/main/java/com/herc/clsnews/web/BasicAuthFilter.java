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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP Basic 认证 + 三档角色。只在 {@code app.security.enabled=true} 时生效。
 *
 * <p>为什么不用 spring-boot-starter-security：那套会自带默认登录页、CSRF、会话等一整串行为，
 * 对一个只有一页静态 HTML 的私用工具来说，需要关掉的东西比要打开的多。这里几十行 Filter
 * 已经把这件事说完，且行为完全可预期。
 *
 * <p>为什么密码为空时拒绝启动而不是放行：最危险的状态不是「没开鉴权」，
 * 而是「以为开了鉴权、实际上密码是空字符串」。宁可起不来。
 *
 * <p>三档角色的划分理由是「能造成多大伤害」，不是「能看多少东西」：
 * <ul>
 *   <li><b>rw</b>（主账号）—— 全部权限。能看所有人的 IP，能把配额烧光。</li>
 *   <li><b>demo</b> —— 能点「分析」（演示的核心就是看模型怎么说），但每天额度封死，
 *       且访客模块整个不可见。密码公开写在登录页上，所以额度就是止损线。</li>
 *   <li><b>ro</b>（只读）—— 只能 GET，看不了访客明细。给朋友看数据用。</li>
 * </ul>
 *
 * <p>注意：Basic 认证的密码是 Base64 明文，不是加密。**必须套 HTTPS**，
 * 否则等同明文密码在公网上裸奔。
 */
@Component
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BasicAuthFilter.class);
    private static final String REALM = "cls-news";

    /** 请求属性名：当前请求的角色，供 /api/status 回给前端决定要不要显示写操作。 */
    public static final String ROLE_ATTR = "cls.role";
    public static final String USER_ATTR = "cls.user";

    /** 角色取值。前端只认这三个字符串，别在别处硬编码。 */
    public static final String ROLE_RW = "rw";
    public static final String ROLE_DEMO = "demo";
    public static final String ROLE_RO = "ro";

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

    /**
     * 只读账号也不给看的路径。
     *
     * <p>{@code /api/visits/logs} 是唯一一处：它是给运维排查用的明细，含每个人的
     * IP、UA、设备指纹。聚合数字（/api/visits）给只读角色看没问题，明细不行。
     */
    private static final Set<String> READ_ONLY_DENY = Set.of("/api/visits/logs");

    /**
     * 演示账号不给看的路径：整个访客模块。
     *
     * <p>比只读账号更严 —— 连聚合数字都不给。演示的用途是看「这条新闻模型怎么判」，
     * 而「今天有多少人在用、累计多少访客」是站主的运营数据，跟演示无关。
     */
    private static final Set<String> DEMO_DENY = Set.of("/api/visits", "/api/visits/logs");

    /**
     * 只读账号唯一允许的非 GET 请求。
     *
     * <p>{@code POST /api/visit/ping} 是访客上报，没有业务副作用，只往统计表里记一笔。
     * 禁掉它，只读账号的访问就不会被计入统计，页面上的「今日访客」会失真。
     */
    private static final Set<String> READ_ONLY_POST = Set.of("/api/visit/ping");

    /**
     * 演示账号唯一允许的写路径：单条分析。
     *
     * <p>用正则而不是 {@code startsWith}：{@code /api/news/1/analyze/../x} 这类拼接
     * 在不同的容器/版本上规范化时机不一致，就不给它留判断空间。
     */
    private static final Pattern ANALYZE_PATH = Pattern.compile("^/api/news/\\d+/analyze$");

    /** 一个账号：user 后面跟冒号再接 password，比较的是整段字节，避免分开比泄露长度。 */
    private record Account(byte[] secret, String role) { }

    private final Map<String, Account> accounts;
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

        Map<String, Account> map = new LinkedHashMap<>();
        map.put(user, new Account((user + ":" + pass).getBytes(StandardCharsets.UTF_8), ROLE_RW));

        addList(map, cfg.getDemo(), ROLE_DEMO, "演示");
        addList(map, cfg.getReadOnly(), ROLE_RO, "只读");

        this.accounts = Map.copyOf(map);
        this.permitPaths = Set.of(cfg.getPermitPaths() == null ? new String[]{"/healthz"} : cfg.getPermitPaths());
        log.info("已开启访问控制：可写账号 {}，演示账号 {}，只读账号 {}，放行路径 {}",
                user,
                map.values().stream().filter(a -> ROLE_DEMO.equals(a.role())).count(),
                map.values().stream().filter(a -> ROLE_RO.equals(a.role())).count(),
                permitPaths);
    }

    /**
     * 解析 {@code user:pass,user2:pass2} 这样的一串账号并加入表。
     *
     * <p>格式错的直接忽略并大声记一笔，不静默跳过：否则「发出去的账号生效了」
     * 只是发送方的错觉，对方一直 401，排查要绕很久。
     */
    private void addList(Map<String, Account> map, String raw, String role, String label) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String entry : raw.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int idx = e.indexOf(':');
            if (idx <= 0 || idx == e.length() - 1) {
                log.warn("忽略格式错误的{}账号（应为 user:pass）: {}", label, e);
                continue;
            }
            String u = e.substring(0, idx).trim();
            String p = e.substring(idx + 1);
            if (map.containsKey(u)) {
                // 同名时不覆盖已有账号：否则一个手误就能把 admin 降成只读
                log.warn("{}账号 {} 与已有账号重名，已忽略", label, u);
                continue;
            }
            map.put(u, new Account((u + ":" + p).getBytes(StandardCharsets.UTF_8), role));
        }
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
                Account acct = match(got);
                if (acct != null) {
                    String role = acct.role();
                    if (!allowed(req, uri, role)) {
                        deny(resp, role);
                        return;
                    }
                    req.setAttribute(ROLE_ATTR, role);
                    req.setAttribute(USER_ATTR, userOf(got));
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

    /**
     * 按用户名取账号，再对整段 {@code user:pass} 做常量时间比较。
     *
     * <p>用户名本身不是秘密（登录框里看得见），所以按名字查表不构成额外泄露；
     * 真正不能按字节短路比较的是密码，所以比较放在 {@link MessageDigest#isEqual}。
     */
    private Account match(byte[] got) {
        int colon = -1;
        for (int i = 0; i < got.length; i++) {
            if (got[i] == ':') { colon = i; break; }
        }
        if (colon <= 0) return null;
        String user = new String(got, 0, colon, StandardCharsets.UTF_8);
        Account acct = accounts.get(user);
        if (acct == null) return null;
        return MessageDigest.isEqual(got, acct.secret()) ? acct : null;
    }

    /** 从已经比对通过的 {@code user:pass} 里取出用户名，只用于日志和排查。 */
    private static String userOf(byte[] got) {
        for (int i = 0; i < got.length; i++) {
            if (got[i] == ':') {
                return new String(got, 0, i, StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /** 按角色判断这个请求能不能过。三种角色的差别全收在这一个方法里。 */
    private boolean allowed(HttpServletRequest req, String uri, String role) {
        if (ROLE_RW.equals(role)) {
            return true;
        }
        if (ROLE_DEMO.equals(role)) {
            return allowedForDemo(req, uri);
        }
        return allowedForReadOnly(req, uri);
    }

    /**
     * 演示账号：GET 全放行（访客模块除外），写操作只允许单条分析。
     *
     * <p>{@code force=true} 单独拒掉：它会无视「结果还新鲜」的保护直接重算，
     * 一模一样的问题再问一遍模型，答案基本不变，但钱是真花了一次。
     * 那个入口本来就只给运维改完 prompt 后重算用。
     */
    private boolean allowedForDemo(HttpServletRequest req, String uri) {
        if (DEMO_DENY.contains(uri)) return false;
        String m = req.getMethod();
        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) return true;
        if (!"POST".equals(m)) return false;
        if (READ_ONLY_POST.contains(uri)) return true;
        if (ANALYZE_PATH.matcher(uri).matches()) {
            return !"true".equalsIgnoreCase(req.getParameter("force"));
        }
        return false;
    }

    private boolean allowedForReadOnly(HttpServletRequest req, String uri) {
        if (READ_ONLY_DENY.contains(uri)) return false;
        String m = req.getMethod();
        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) return true;
        return "POST".equals(m) && READ_ONLY_POST.contains(uri);
    }

    /**
     * 拒绝时把原因说清楚。
     *
     * <p>三档角色用的是同一个 403，但文案分开写：演示账号看到「只读账号」会以为是配置错了，
     * 而他其实有分析权限，只是这一条路径不在范围内。
     */
    private void deny(HttpServletResponse resp, String role) throws IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("text/plain;charset=UTF-8");
        String msg = ROLE_DEMO.equals(role)
                ? "演示账号只能看新闻和点「分析」，访客数据与运维操作不可用"
                : "该账号为只读账号，不能执行这个操作";
        resp.getWriter().write(msg);
    }

    /** 白名单路径里出现穿越/重复斜杠一律不放行，交给鉴权流程处理。 */
    private boolean looksLikeTraversal(String uri) {
        return uri.contains("..") || uri.contains("//") || uri.contains(";");
    }
}
