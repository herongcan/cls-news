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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP Basic 认证 + 三档角色（rw / guest / ro）。只在 {@code app.security.enabled=true} 时生效。
 *
 * <p>账号在启动时从配置解析进内存 Map，不查库：这是私用工具的闸门，不是权限系统。
 *
 * <p>三档按「能造成多大伤害」划分，不是「能看多少东西」：
 * <ul>
 *   <li><b>rw</b> 主账号：全部权限，能看所有人的 IP，能把配额烧光。</li>
 *   <li><b>guest</b> 游客：能点「分析」，但每天额度封死，访客模块整个不可见。
 *       密码公开写在登录页上，额度就是止损线。</li>
 *   <li><b>ro</b> 只读：只能 GET，看不到访客明细。</li>
 * </ul>
 *
 * <p>Basic 的密码是 Base64 明文，不是加密：**必须套 HTTPS**。
 */
@Component
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BasicAuthFilter.class);
    private static final String REALM = "cls-news";

    /** 请求属性名：当前请求的角色与用户名。 */
    public static final String ROLE_ATTR = "cls.role";
    public static final String USER_ATTR = "cls.user";

    /** 角色取值。前端只认这三个字符串。 */
    public static final String ROLE_RW = "rw";
    public static final String ROLE_GUEST = "guest";
    public static final String ROLE_RO = "ro";

    /**
     * 页面本身放行。
     *
     * <p>移动端内置浏览器（微信 / QQ / UC、Android WebView）遇到 401 + {@code WWW-Authenticate}
     * 不会弹原生输入框，只渲染一张白页。所以凭证由页面自己收：index.html 只是个空壳，
     * 数据都在 /api/** 后面。
     *
     * <p>{@code /legacy.html} 是旧的手写页面，由 sync-to-jar 在首次覆盖时留底。它同样
     * 是空壳（资源全部内联），放行才能作为回滚入口直接打开。
     */
    private static final Set<String> PAGE_PATHS = Set.of("/", "/index.html", "/legacy.html");

    /**
     * 前端构建产物的路径前缀。
     *
     * <p>页面免鉴权只解决了一半问题：uni-app 打包后 JS 与 CSS 是独立的 {@code /assets/*}
     * 文件，而浏览器加载 {@code <script src>} / {@code <link href>} 时**不会**带
     * Authorization 头。这些资源一旦要求鉴权，页面就只剩一张白屏 —— 旧页面把所有
     * CSS/JS 内联进 index.html，所以这个坑直到换成构建产物才暴露。
     *
     * <p>放行是安全的：产物里只有渲染逻辑，不含任何数据，数据全在 /api/** 后面。
     */
    private static final String ASSET_PREFIX = "/assets/";

    /** 只读账号也不给看的路径：访客明细含所有人的 IP 和 UA，聚合数可以给。 */
    private static final Set<String> READ_ONLY_DENY = Set.of("/api/visits/logs");

    /** 游客不给看的路径：整个访客模块，连聚合数都不给。 */
    private static final Set<String> GUEST_DENY = Set.of("/api/visits", "/api/visits/logs");

    /** 只读账号唯一允许的非 GET：访客上报没有副作用，禁掉会让统计失真。 */
    private static final Set<String> READ_ONLY_POST = Set.of("/api/visit/ping");

    /** 游客唯一允许的写路径。用正则而不是 startsWith，避免路径拼接绕过。 */
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

        addList(map, cfg.getGuest(), ROLE_GUEST, "游客");
        addList(map, cfg.getReadOnly(), ROLE_RO, "只读");

        this.accounts = Map.copyOf(map);
        this.permitPaths = Set.of(cfg.getPermitPaths() == null ? new String[]{"/healthz"} : cfg.getPermitPaths());
        // 打印用户名而不是数量：账号配错（重名、漏写变量）时一眼就能看出来
        log.info("已开启访问控制：可写 {}，游客 {}，只读 {}，放行路径 {}",
                user, namesOf(map, ROLE_GUEST), namesOf(map, ROLE_RO), permitPaths);
    }

    private static List<String> namesOf(Map<String, Account> map, String role) {
        return map.entrySet().stream()
                .filter(e -> role.equals(e.getValue().role()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 解析 {@code user:pass,user2:pass2} 加入表。格式错的打 warn 跳过，不静默生效。 */
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
                // 同名不覆盖：先到先得，避免手误把已有账号降级
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
        if ((permitPaths.contains(uri) || PAGE_PATHS.contains(uri) || isPublicAsset(uri))
                && !looksLikeTraversal(uri)) {
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
        // 只给非 API 请求发 challenge：否则 Safari 会在页内登录层之上再弹一个原生框，
        // 输完还关不掉。
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
        if (ROLE_GUEST.equals(role)) {
            return allowedForGuest(req, uri);
        }
        return allowedForReadOnly(req, uri);
    }

    /**
     * 游客：GET 放行（访客模块除外），写只允许单条分析。
     *
     * <p>{@code force=true} 单独拒掉：它是运维改完 prompt 后强制重算的入口，
     * 游客点一次就是重复烧一次钱。
     */
    private boolean allowedForGuest(HttpServletRequest req, String uri) {
        if (GUEST_DENY.contains(uri)) return false;
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

    /** 403 文案按角色分开：游客看到「只读账号」会以为配置错了，他其实有分析权限。 */
    private void deny(HttpServletResponse resp, String role) throws IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("text/plain;charset=UTF-8");
        String msg = ROLE_GUEST.equals(role)
                ? "游客账号只能看新闻和点「分析」，访客数据与运维操作不可用"
                : "该账号为只读账号，不能执行这个操作";
        resp.getWriter().write(msg);
    }

    /** 白名单路径里带 .. / // / ; 一律不放行，交给鉴权流程处理。 */
    private boolean looksLikeTraversal(String uri) {
        return uri.contains("..") || uri.contains("//") || uri.contains(";");
    }

    /**
     * 是否是可以匿名取的前端资源。
     *
     * <p>除了前缀与路径穿越检查，还把字符限制在构建产物实际会用到的集合（字母数字与
     * {@code . _ - /}）。出现 {@code %} 或 {@code \} 说明是编码过的绕行尝试
     * （例如 {@code /assets/..%2fapi/status}），直接拒绝 —— 前缀匹配之后路径会交给
     * 下游容器再解码一次，能不能挡住取决于具体实现，这里不赌。
     */
    private boolean isPublicAsset(String uri) {
        // 临时实验：故意回退成「只放行 HTML」的状态，用来验证 CI 门禁能拦住
        // 2026-11-22 那次白屏回归。验证完这个分支即删除，不合并。
        // 条件写成 uri != null 是为了让编译器不把后续代码判成 unreachable
        // （用 if (true) 会直接编译失败，那就测不到断言了）。
        if (uri != null) {
            return false;
        }
        if (!uri.startsWith(ASSET_PREFIX) || looksLikeTraversal(uri)) {
            return false;
        }
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '/';
            if (!safe) {
                return false;
            }
        }
        return true;
    }
}
