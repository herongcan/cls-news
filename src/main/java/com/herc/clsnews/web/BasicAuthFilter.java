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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 三档角色鉴权。只在 {@code app.security.enabled=true} 时生效。
 *
 * <p>为什么不用 spring-boot-starter-security：那套会自带默认登录页、CSRF、会话等一整串行为，
 * 对一个只有一页前端的私用工具来说，需要关掉的东西比要打开的多。这里一百多行 Filter
 * 已经把这件事说完，且行为完全可预期。
 *
 * <p>凭证只有一种形态：**Bearer token**。浏览器、小程序、定时脚本全都走它。
 *
 * <p>Basic 头在这里**一律不接受**，唯一的例外是 {@code POST /api/auth/token} ——
 * 而那条路径在下面被直接放行，压根不经过本过滤器（由
 * {@link AuthController#token} 自己校验并限流）。所以这个类里没有 Basic 分支。
 *
 * <p>为什么要收掉：密码是长期凭证，Basic 能直连业务接口时，拿到密码就等于永久拿到了
 * 全部权限（可以 curl、可以绕过令牌的一切作废手段）。收掉之后密码只剩「换一张 30 分钟的票」
 * 这一个作用，泄露的影响面从「永久」缩到「一次换票」—— 而且换票被
 * {@link LoginThrottle} 卡着。运维的 {@code curl} 也就多一步：
 * <pre>{@code
 * TOK=$(curl -s -X POST -u admin:密码 $H/api/auth/token | jq -r .accessToken)
 * curl -H "Authorization: Bearer $TOK" $H/api/status
 * }</pre>
 *
 * <p>三档角色的划分理由是「能造成多大伤害」，不是「能看多少东西」：
 * <ul>
 *   <li><b>rw</b>（主账号）—— 全部权限。能看所有人的 IP，能把配额烧光。</li>
 *   <li><b>guest</b> —— 能点「分析」（演示的核心就是看模型怎么说），但每天额度封死，
 *       且访客模块整个不可见。密码公开写在登录页上，所以额度就是止损线。</li>
 *   <li><b>ro</b>（只读）—— 只能 GET，看不了访客明细。给朋友看数据用。</li>
 * </ul>
 *
 * <p>注意：Basic 的密码是 Base64 明文，不是加密。**必须套 HTTPS**，
 * 否则等同明文密码在公网上裸奔。token 也是同样道理 —— 明文 Bearer token 被截获即可冒用。
 */
@Component
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BasicAuthFilter.class);

    /** 请求属性名：当前请求的角色，供 /api/status 回给前端决定要不要显示写操作。 */
    public static final String ROLE_ATTR = "cls.role";
    public static final String USER_ATTR = "cls.user";

    /** 角色取值。前端只认这三个字符串，别在别处硬编码。 */
    public static final String ROLE_RW = "rw";
    public static final String ROLE_GUEST = "guest";
    public static final String ROLE_RO = "ro";

    /**
     * 页面本身必须放行。
     *
     * <p>移动端浏览器（微信、QQ、UC 的内置内核，以及不少 Android WebView）收到
     * 401 + {@code WWW-Authenticate} 之后**不会弹出原生输入框**，而是直接把 401
     * 渲染成一张白底页面 —— 电脑上一切正常，同事用手机点开只看到「需要登录」三个字。
     *
     * <p>所以改成由页面自己收凭证：HTML 先加载出来，登录框做在页面里。
     * 代价是 index.html 本身免认证就能拿到 —— 它只是个空壳，所有数据都在 /api/** 后面。
     *
     * <p>{@code /legacy.html} 也要放行：它是回滚入口，自己白屏就没法回滚了。
     */
    private static final Set<String> PAGE_PATHS = Set.of("/", "/index.html", "/legacy.html");

    /**
     * 换发凭证的端点，必须允许无有效凭证访问。
     *
     * <p>不放行就死锁：token 过期了想换新的，得先有有效 token。
     * 放行的前提是这三个端点自己校验 —— {@code /token} 验 Basic 原文，
     * {@code /refresh} 验一次性 refresh，{@code /logout} 只是删一条记录。
     *
     * <p>限定 POST：GET /api/auth/token 没有意义，不该因为路径匹配被放过去。
     */
    private static final Set<String> AUTH_ENDPOINTS = Set.of("/api/auth/token", "/api/auth/refresh", "/api/auth/logout");

    /**
     * 只读账号也不给看的路径。
     *
     * <p>{@code /api/visits/logs} 是唯一一处：它是给运维排查用的明细，含每个人的
     * IP、UA、设备指纹。聚合数字（/api/visits）给只读角色看没问题，明细不行。
     */
    private static final Set<String> READ_ONLY_DENY = Set.of("/api/visits/logs");

    /**
     * 游客账号不给看的路径：整个访客模块，以及主线体检。
     *
     * <p>比只读账号更严 —— 连聚合数字都不给。演示的用途是看「这条新闻模型怎么判」，
     * 而「今天有多少人在用、累计多少访客」是站主的运营数据，跟演示无关。
     *
     * <p>{@code /api/agent/health} 同理：它报的是全站 token 消耗、key 有没有配、
     * 有没有僵死的 running 行。这些是运维视角，演示账号看了既帮不上忙，
     * 又把"这个站一天花多少钱"摊给了外人。
     */
    private static final Set<String> GUEST_DENY =
            Set.of("/api/visits", "/api/visits/logs", "/api/agent/health");

    /**
     * 只读账号唯一允许的非 GET 请求。
     *
     * <p>{@code POST /api/visit/ping} 是访客上报，没有业务副作用，只往统计表里记一笔。
     * 禁掉它，只读账号的访问就不会被计入统计，页面上的「今日访客」会失真。
     */
    private static final Set<String> READ_ONLY_POST = Set.of("/api/visit/ping");

    /**
     * 游客账号唯一允许的写路径：单条分析，以及主线生成。
     *
     * <p>用正则而不是 {@code startsWith}：{@code /api/news/1/analyze/../x} 这类拼接
     * 在不同的容器/版本上规范化时机不一致，就不给它留判断空间。
     */
    private static final Pattern ANALYZE_PATH = Pattern.compile("^/api/news/\\d+/analyze$");

    /**
     * 游客唯一允许的写接口。
     *
     * <p>{@code /api/agent/ask} 是主线生成，单价约为单条分析的 20 倍。放行它的理由不是
     * "游客也该花钱"，而是 {@code app.agent.guest-daily-cap}（默认 5）本来就是为这件事配的：
     * 额度在 {@code AgentService.checkQuota} 里按 actor 扣，真正的钱闸在那里。
     * 白名单这边只回答"这个角色能不能碰这条路径"——不许的话那个配置项就永远不生效，
     * 界面上却是"游客也有主线"，两边对不上。
     *
     * <p>只读账号（{@code ro}）**不在**这里：它的定义就是不产生花费。
     * 它仍然能读 {@code precheck} 与 {@code runs}，看得到已有结论。
     */
    private static final Set<String> GUEST_POST = Set.of("/api/visit/ping", "/api/agent/ask");

    /** 前端构建产物，浏览器加载时不会带任何凭证头。 */
    private static final String ASSET_PREFIX = "/assets/";

    /**
     * 浏览器自己会来要、但页面上根本没引用的文件。
     *
     * <p>为什么要单独放行：Chrome 打开任何页面都会自动请求 {@code /favicon.ico}。
     * 它不在放行名单里 → 401 + {@code WWW-Authenticate} → Chrome 判定「整站需要 HTTP 认证」，
     * 直接在页面上盖一层原生登录框（标题是「登录 http://host」那个），页面自己的登录层
     * 连露脸的机会都没有，而且在框里输对了也关不掉。实测复现：{@code curl -D- /favicon.ico}。
     *
     * <p>放行后这些路径由 Spring 的静态资源处理器返回 404，浏览器记一条日志就过去了 ——
     * 比弹一个关不掉的框好。
     */
    private static final Set<String> BROWSER_PROBES = Set.of(
            "/favicon.ico", "/robots.txt",
            "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png");

    private final TokenStore tokens;
    private final Set<String> permitPaths;

    public BasicAuthFilter(AppProps props, TokenStore tokens) {
        this.tokens = tokens;
        this.permitPaths = Set.of(props.getSecurity().getPermitPaths() == null
                ? new String[]{"/healthz"} : props.getSecurity().getPermitPaths());
        log.info("放行路径 {}，换发凭证端点 {}", permitPaths, AUTH_ENDPOINTS);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        String uri = req.getRequestURI();

        // 白名单必须用「完全相等」而不是 startsWith。实测过：写成 startsWith 时，
        // /healthz/../api/status 会因为前缀是 /healthz 而被放行，然后交给下游做路径规范化——
        // 能不能挡住完全取决于 Tomcat 的实现，等于把安全交给别人的默认值。
        // 另外显式拒绝带 .. 或 // 的请求：规范化时机在不同容器/版本上不一致，不赌。
        if ((permitPaths.contains(uri) || PAGE_PATHS.contains(uri)
                || BROWSER_PROBES.contains(uri)
                || isPublicAsset(uri) || isAuthEndpoint(req, uri))
                && !looksLikeTraversal(uri)) {
            chain.doFilter(req, resp);
            return;
        }

        String header = req.getHeader("Authorization");

        // Bearer 是唯一被接受的凭证。Basic 换票走的是上面那条白名单，
        // 压根到不了这里 —— 所以下面那个 Basic 分支只负责说「不行」。
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            TokenStore.Principal p = tokens.resolveAccess(header.substring(7).trim());
            if (p != null) {
                if (!allowed(req, uri, p.role())) {
                    deny(resp, p.role());
                    return;
                }
                req.setAttribute(ROLE_ATTR, p.role());
                req.setAttribute(USER_ATTR, p.user());
                chain.doFilter(req, resp);
                return;
            }
            // token 无效直接 401，不落到下面的 Basic 分支：
            // 带着过期 token 的请求再回头试 Basic 没有意义，前端拿到 401 会自己刷新。
            //
            // 这条 WARN 是排查「莫名其妙要重新登录」的唯一线索：它证明请求**确实带了**
            // 令牌，是服务端不认（被登出、换过库、或签发它的那次进程已经不在了）。
            // 反过来，如果日志里没有这条、页面却在弹重登，那就是前端根本没把令牌发出来
            // （本地存储被清）—— 两种情况的修法完全不同，别混。
            log.warn("Bearer 令牌不被认可：uri={} ua={}", uri, agentOf(req));
            unauthorized(resp);
            return;
        }

        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            // Basic 只对 POST /api/auth/token 有意义，而那条路径在上面已经被放行了，
            // 走不到这里。所以此刻出现的 Basic 一定是「拿密码直连业务接口」——
            // 收掉它不是洁癖：密码是长期凭证，一旦能直连，令牌的所有作废手段
            //（登出、旋转、过期）都能被绕过，等于令牌体系白做。
            //
            // 文案写成「该怎么做」而不是「认证失败」：老脚本沿用 curl -u 时会拿到这句，
            // 照着改一步就能跑，不用去翻文档猜哪里错了。
            log.warn("拒绝 Basic 直连业务接口：uri={} ua={}", uri, agentOf(req));
            basicRejected(resp);
            return;
        }

        unauthorized(resp);
    }

    private static boolean isAuthEndpoint(HttpServletRequest req, String uri) {
        return AUTH_ENDPOINTS.contains(uri) && "POST".equals(req.getMethod());
    }

    /**
     * 401。**故意不发 {@code WWW-Authenticate}**。
     *
     * <p>原来只在非 /api/ 路径上发 challenge，想把「浏览器原生弹框」留给页面请求。
     * 实际效果是反的：只要有任何一条页面侧的请求返回 401（最先踩到的是浏览器自动请求的
     * {@code /favicon.ico}），Chrome / Safari 就会在页面之上盖一层原生认证框，
     * 而页面自己的登录层还在下面 —— 用户在原生框里输对了也关不掉，输错了还会一直重弹。
     *
     * <p>这个项目只有一个页面、一个登录层，凭证由页面收、由 {@code /api/auth/token} 换发，
     * 所以不需要（也不想要）任何原生认证交互。运维用 {@code curl -u} 照样能过，
     * 那走的是请求头，与 challenge 无关。
     */
    private void unauthorized(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("text/plain;charset=UTF-8");
        resp.getWriter().write("需要登录");
    }

    /**
     * 带着 Basic 打业务接口时的 401。
     *
     * <p>和 {@link #unauthorized} 分开写，是为了让「凭证形态不对」和「没有凭证」
     * 在日志/响应里可区分 —— 前者通常意味着调用方还在用旧姿势，后者才是真的未登录。
     *
     * <p>同样**不发** {@code WWW-Authenticate}：理由见 {@link #unauthorized}。
     */
    private void basicRejected(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("text/plain;charset=UTF-8");
        resp.getWriter().write("Basic 凭证只能用于 POST /api/auth/token，请先换取令牌再用 Bearer 访问");
    }

    /** 日志里只留 UA 前 60 字：完整 UA 可能很长，排查时前 60 字足够区分浏览器/脚本。 */
    private static String agentOf(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        if (ua == null) return "-";
        return ua.length() <= 60 ? ua : ua.substring(0, 60);
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
     * 游客账号：GET 全放行（访客模块除外），写操作只允许单条分析。
     *
     * <p>{@code force=true} 单独拒掉：它会无视「结果还新鲜」的保护直接重算，
     * 一模一样的问题再问一遍模型，答案基本不变，但钱是真花了一次。
     * 那个入口本来就只给运维改完 prompt 后重算用。
     */
    private boolean allowedForGuest(HttpServletRequest req, String uri) {
        if (GUEST_DENY.contains(uri)) return false;
        String m = req.getMethod();
        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) return true;
        if (!"POST".equals(m)) return false;
        if (GUEST_POST.contains(uri)) return true;
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
     * <p>三档角色用的是同一个 403，但文案分开写：游客看到「只读账号」会以为是配置错了，
     * 而他其实有分析权限，只是这一条路径不在范围内。
     */
    private void deny(HttpServletResponse resp, String role) throws IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("text/plain;charset=UTF-8");
        String msg = ROLE_GUEST.equals(role)
                ? "游客账号只能看新闻和点「分析」，访客数据与运维操作不可用"
                : "该账号为只读账号，不能执行这个操作";
        resp.getWriter().write(msg);
    }

    /** 白名单路径里出现穿越/重复斜杠一律不放行，交给鉴权流程处理。 */
    private boolean looksLikeTraversal(String uri) {
        return uri.contains("..") || uri.contains("//") || uri.contains(";");
    }

    /**
     * 是否是可以匿名取的前端资源。
     *
     * <p>浏览器加载 {@code <script src>} / {@code <link href>} 时**不会**带 Authorization 头，
     * 所以不放行的话，页面能下载但一行 JS 都执行不了 —— 表现是整页白屏。
     * 旧版把 CSS/JS 全部内联在 index.html 里，从来没有独立资源请求，这个洞直到换成
     * 构建产物（2026-11-22）才暴露。
     *
     * <p>除了前缀与路径穿越检查，还把字符限制在构建产物实际会用到的集合（字母数字与
     * {@code . _ - /}）。出现 {@code %} 或 {@code \} 说明是编码过的绕行尝试
     * （例如 {@code /assets/..%2fapi/status}），直接拒绝 —— 前缀匹配之后路径会交给
     * 下游容器再解码一次，能不能挡住取决于具体实现，这里不赌。
     */
    private boolean isPublicAsset(String uri) {
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
