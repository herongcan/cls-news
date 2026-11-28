package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三档角色的权限矩阵，以及「什么凭证能进哪条路径」。
 *
 * <p>这套规则散在一个 {@code allowed()} 方法里，改一行就可能让游客看到全站 IP、
 * 或者让只读账号点掉一次模型调用。CI 里的冒烟只覆盖了其中三条（guest 看访客 403 等），
 * 剩下的组合没有任何东西在看着。
 *
 * <p>为什么直接驱动 Filter 而不起 MockMvc：这里要断言的是**过滤器自己的判断**，
 * 包括「401 绝对不能带 WWW-Authenticate」这种响应头级别的行为。
 *
 * <p>2026-11-22 起角色用例全部改走 Bearer：Basic 已经不允许直连业务接口，
 * 继续用它来测角色矩阵，测的就是一条真实客户端不会走的路径。
 */
class AuthMatrixTest {

    /** 令牌落库后，这个用例也需要一个真实（临时）库，键与生产同源 */
    @TempDir
    Path dir;

    private BasicAuthFilter filter;
    private TokenStore tokens;

    @BeforeEach
    void setUp() throws Exception {
        AppProps props = new AppProps();
        props.getSecurity().setEnabled(true);
        props.getSecurity().setUsername("admin");
        props.getSecurity().setPassword("admin");
        props.getSecurity().setGuest("guest:guest");
        props.getSecurity().setReadOnly("ro:ro123");

        tokens = new TokenStore(TestDb.create(dir), props);
        filter = new BasicAuthFilter(props, tokens);
    }

    /** 一次请求的结果：是否被放行、状态码、有没有发 challenge、响应体。 */
    private record Result(boolean passed, int status, String challenge, String body) {
        boolean denied(int expected) {
            return !passed && status == expected;
        }
    }

    private Result call(String method, String uri) throws Exception {
        return run(new MockHttpServletRequest(method, uri));
    }

    /**
     * @param token 直接塞一个 Bearer；{@code null} 表示不带凭证
     * @param force 给 analyze 用的 query 参数（MockHttpServletRequest 不会自己解析 query string）
     */
    private Result callWithToken(String method, String uri, String token, String force) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        if (force != null) {
            req.setParameter("force", force);
        }
        return run(req);
    }

    /** 显式用 Basic 头，用于验证「它现在只对换令牌有意义」。 */
    private Result callWithBasic(String method, String uri, String credentials) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.addHeader("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        return run(req);
    }

    private Result run(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        filter.doFilter(req, resp, (rq, rs) -> passed.set(true));
        return new Result(passed.get(), resp.getStatus(), resp.getHeader("WWW-Authenticate"),
                resp.getContentAsString());
    }

    private String tokenOf(String user, String role) {
        return tokens.issue(user, role).accessToken();
    }

    // ---------- 免认证 ----------

    @Test
    @DisplayName("页面、构建产物、浏览器探测路径、健康检查都免凭证")
    void publicPathsNeedNoCredentials() throws Exception {
        for (String uri : new String[]{"/", "/index.html", "/legacy.html", "/healthz",
                "/favicon.ico", "/robots.txt", "/assets/index-abc123.js"}) {
            assertThat(call("GET", uri).passed()).as(uri + " 必须能匿名加载").isTrue();
        }
        assertThat(call("POST", "/api/auth/token").passed())
                .as("换令牌本身不能要令牌，否则死锁").isTrue();
        assertThat(callWithBasic("POST", "/api/auth/token", "admin:admin").passed())
                .as("Basic 换令牌是唯一保留的用法").isTrue();
    }

    // ---------- 无凭证 / 凭证形态不对 ----------

    @Test
    @DisplayName("API 无凭证 401，且绝不发 WWW-Authenticate")
    void apiWithoutCredentialsIsChallengeless401() throws Exception {
        Result r = call("GET", "/api/status");
        assertThat(r.denied(401)).isTrue();
        assertThat(r.challenge())
                .as("发了 challenge，浏览器会盖一层原生登录框，页内登录层就再也用不了")
                .isNull();
    }

    @Test
    @DisplayName("Basic 直连业务接口一律 401 —— 哪怕密码是对的")
    void basicIsRejectedOnBusinessPaths() throws Exception {
        Result right = callWithBasic("GET", "/api/status", "admin:admin");
        assertThat(right.denied(401))
                .as("密码正确也不行：密码是长期凭证，直连就等于绕过令牌体系").isTrue();
        assertThat(right.body()).as("文案要告诉调用方该怎么改，而不是只说「需要登录」")
                .contains("/api/auth/token");
        assertThat(right.challenge()).isNull();

        assertThat(callWithBasic("GET", "/api/status", "admin:wrong").denied(401)).isTrue();
        assertThat(callWithBasic("GET", "/api/news", "guest:guest").denied(401))
                .as("游客账号的 Basic 也不能直连业务接口").isTrue();
    }

    @Test
    @DisplayName("GET /api/auth/token 不放行：换令牌只能是 POST")
    void getOnTokenEndpointIsRejected() throws Exception {
        assertThat(callWithBasic("GET", "/api/auth/token", "admin:admin").denied(401))
                .as("路径匹配按方法限定，否则 GET 也会把密码头带进日志/缓存").isTrue();
    }

    // ---------- rw ----------

    @Test
    @DisplayName("rw：读写全通，包括访客明细和 force 重算")
    void rwHasEverything() throws Exception {
        String rw = tokenOf("admin", "rw");
        assertThat(callWithToken("GET", "/api/status", rw, null).passed()).isTrue();
        assertThat(callWithToken("GET", "/api/visits", rw, null).passed()).isTrue();
        assertThat(callWithToken("GET", "/api/visits/logs", rw, null).passed())
                .as("明细含每个人的 IP/UA，主账号要能排查").isTrue();
        assertThat(callWithToken("POST", "/api/news/1/analyze", rw, "true").passed()).isTrue();
        assertThat(callWithToken("POST", "/api/analysis/retry-failed", rw, null).passed()).isTrue();
    }

    // ---------- guest ----------

    @Test
    @DisplayName("guest：能看新闻、能点分析，看不到任何访客数据，不能 force 重算")
    void guestMatrix() throws Exception {
        String guest = tokenOf("guest", "guest");
        assertThat(callWithToken("GET", "/api/news", guest, null).passed()).isTrue();
        assertThat(callWithToken("GET", "/api/status", guest, null).passed()).isTrue();
        assertThat(callWithToken("POST", "/api/news/1/analyze", guest, null).passed())
                .as("演示的核心就是看模型怎么说").isTrue();
        assertThat(callWithToken("POST", "/api/visit/ping", guest, null).passed())
                .as("访客上报没有副作用，禁掉的话游客访问不计入统计").isTrue();
        assertThat(callWithToken("GET", "/api/agent/precheck", guest, null).passed())
                .as("报价免费，游客当然要能看到「还剩几次」").isTrue();
        assertThat(callWithToken("POST", "/api/agent/ask", guest, null).passed())
                .as("app.agent.guest-daily-cap 就是为这条路配的；真钱闸在 AgentService 的配额里")
                .isTrue();

        assertThat(callWithToken("GET", "/api/visits", guest, null).denied(403))
                .as("访客数字是站主的运营数据，与演示无关").isTrue();
        assertThat(callWithToken("GET", "/api/visits/logs", guest, null).denied(403)).isTrue();
        assertThat(callWithToken("GET", "/api/agent/health", guest, null).denied(403))
                .as("体检里有全站 token 消耗和 key 状态，这是运维视角，不是演示内容").isTrue();
        assertThat(callWithToken("POST", "/api/news/1/analyze", guest, "true").denied(403))
                .as("force 会无视新鲜度保护再花一次钱，只给运维").isTrue();
        assertThat(callWithToken("POST", "/api/news/1", guest, null).denied(403))
                .as("非 analyze 的写路径一个都不给").isTrue();
        assertThat(callWithToken("POST", "/api/analysis/retry-failed", guest, null).denied(403)).isTrue();
    }

    // ---------- ro ----------

    @Test
    @DisplayName("ro：只读，看得到访客聚合、看不到明细、不能点分析")
    void readOnlyMatrix() throws Exception {
        String ro = tokenOf("ro", "ro");
        assertThat(callWithToken("GET", "/api/news", ro, null).passed()).isTrue();
        assertThat(callWithToken("GET", "/api/visits", ro, null).passed())
                .as("聚合数字给朋友看没问题").isTrue();
        assertThat(callWithToken("POST", "/api/visit/ping", ro, null).passed())
                .as("只读账号的访问也要计入统计，否则数字失真").isTrue();

        assertThat(callWithToken("GET", "/api/visits/logs", ro, null).denied(403))
                .as("明细里有别人的 IP 和 UA").isTrue();
        assertThat(callWithToken("POST", "/api/news/1/analyze", ro, null).denied(403))
                .as("只读账号点分析就是烧钱").isTrue();
        assertThat(callWithToken("GET", "/api/agent/precheck", ro, null).passed())
                .as("只读账号看得到已有结论和报价，只是不能点下去").isTrue();
        assertThat(callWithToken("POST", "/api/agent/ask", ro, null).denied(403))
                .as("主线一次约 1 万 token，只读账号的定义就是不产生花费").isTrue();
        assertThat(callWithToken("GET", "/api/agent/health", ro, null).passed())
                .as("体检是纯读：不调模型、不写库，只读账号没有理由被挡在门外").isTrue();
    }

    // ---------- 令牌 ----------

    @Test
    @DisplayName("Bearer 令牌受同一套角色约束，不是「有令牌就全通」")
    void bearerTokenKeepsRole() throws Exception {
        String guest = tokenOf("guest", "guest");
        assertThat(callWithToken("GET", "/api/news", guest, null).passed()).isTrue();
        assertThat(callWithToken("GET", "/api/visits", guest, null).denied(403))
                .as("换了令牌也不能越权，角色来自账号而不是令牌形式").isTrue();

        assertThat(callWithToken("GET", "/api/visits/logs", tokenOf("admin", "rw"), null).passed()).isTrue();
    }

    @Test
    @DisplayName("乱造的令牌 401")
    void bogusTokenIs401() throws Exception {
        assertThat(callWithToken("GET", "/api/status", "nope", null).denied(401)).isTrue();
    }

    // ---------- 白名单不能被绕过 ----------

    @Test
    @DisplayName("带 .. 或 // 的路径不能被白名单放过")
    void traversalIsNotWhitelisted() throws Exception {
        assertThat(call("GET", "/healthz/../api/status").denied(401)).isTrue();
        assertThat(call("GET", "/assets/..%2fapi/status").denied(401))
                .as("% 会先过一层解码，不能赌下游容器的规范化时机").isTrue();
        assertThat(call("GET", "//api/status").denied(401)).isTrue();
    }
}
