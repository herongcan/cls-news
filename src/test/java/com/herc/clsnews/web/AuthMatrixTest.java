package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三档角色的权限矩阵。
 *
 * <p>这套规则散在一个 {@code allowed()} 方法里，改一行就可能让游客看到全站 IP、
 * 或者让只读账号点掉一次模型调用。CI 里的冒烟只覆盖了其中三条（guest 看访客 403 等），
 * 剩下的组合没有任何东西在看着。
 *
 * <p>为什么直接驱动 Filter 而不起 MockMvc：这里要断言的是**过滤器自己的判断**，
 * 包括「401 绝对不能带 WWW-Authenticate」这种响应头级别的行为。
 */
class AuthMatrixTest {

    private BasicAuthFilter filter;
    private TokenStore tokens;

    @BeforeEach
    void setUp() {
        AppProps props = new AppProps();
        props.getSecurity().setEnabled(true);
        props.getSecurity().setUsername("admin");
        props.getSecurity().setPassword("admin");
        props.getSecurity().setGuest("guest:guest");
        props.getSecurity().setReadOnly("ro:ro123");

        AccountStore accounts = new AccountStore(props);
        tokens = new TokenStore(props);
        filter = new BasicAuthFilter(props, accounts, tokens);
    }

    /** 一次请求的结果：是否被放行、状态码、有没有发 challenge。 */
    private record Result(boolean passed, int status, String challenge) {
        boolean denied(int expected) {
            return !passed && status == expected;
        }
    }

    private Result call(String method, String uri) throws Exception {
        return call(method, uri, null, null);
    }

    /**
     * @param basic user:pass；null 表示不带凭证
     * @param force 给 analyze 用的 query 参数（MockHttpServletRequest 不会自己解析 query string）
     */
    private Result call(String method, String uri, String basic, String force) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (basic != null) {
            req.addHeader("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        }
        if (force != null) {
            req.setParameter("force", force);
        }
        return run(req);
    }

    private Result callWithToken(String method, String uri, String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.addHeader("Authorization", "Bearer " + token);
        return run(req);
    }

    private Result run(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        filter.doFilter(req, resp, (rq, rs) -> passed.set(true));
        return new Result(passed.get(), resp.getStatus(), resp.getHeader("WWW-Authenticate"));
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
    }

    // ---------- 无凭证 ----------

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
    @DisplayName("错误密码 401")
    void wrongPasswordIs401() throws Exception {
        assertThat(call("GET", "/api/status", "admin:wrong", null).denied(401)).isTrue();
    }

    // ---------- rw ----------

    @Test
    @DisplayName("rw：读写全通，包括访客明细和 force 重算")
    void rwHasEverything() throws Exception {
        assertThat(call("GET", "/api/status", "admin:admin", null).passed()).isTrue();
        assertThat(call("GET", "/api/visits", "admin:admin", null).passed()).isTrue();
        assertThat(call("GET", "/api/visits/logs", "admin:admin", null).passed())
                .as("明细含每个人的 IP/UA，主账号要能排查").isTrue();
        assertThat(call("POST", "/api/news/1/analyze", "admin:admin", "true").passed()).isTrue();
        assertThat(call("POST", "/api/analysis/retry-failed", "admin:admin", null).passed()).isTrue();
    }

    // ---------- guest ----------

    @Test
    @DisplayName("guest：能看新闻、能点分析，看不到任何访客数据，不能 force 重算")
    void guestMatrix() throws Exception {
        assertThat(call("GET", "/api/news", "guest:guest", null).passed()).isTrue();
        assertThat(call("GET", "/api/status", "guest:guest", null).passed()).isTrue();
        assertThat(call("POST", "/api/news/1/analyze", "guest:guest", null).passed())
                .as("演示的核心就是看模型怎么说").isTrue();
        assertThat(call("POST", "/api/visit/ping", "guest:guest", null).passed())
                .as("访客上报没有副作用，禁掉的话游客访问不计入统计").isTrue();

        assertThat(call("GET", "/api/visits", "guest:guest", null).denied(403))
                .as("访客数字是站主的运营数据，与演示无关").isTrue();
        assertThat(call("GET", "/api/visits/logs", "guest:guest", null).denied(403)).isTrue();
        assertThat(call("POST", "/api/news/1/analyze", "guest:guest", "true").denied(403))
                .as("force 会无视新鲜度保护再花一次钱，只给运维").isTrue();
        assertThat(call("POST", "/api/news/1", "guest:guest", null).denied(403))
                .as("非 analyze 的写路径一个都不给").isTrue();
        assertThat(call("POST", "/api/analysis/retry-failed", "guest:guest", null).denied(403)).isTrue();
    }

    // ---------- ro ----------

    @Test
    @DisplayName("ro：只读，看得到访客聚合、看不到明细、不能点分析")
    void readOnlyMatrix() throws Exception {
        assertThat(call("GET", "/api/news", "ro:ro123", null).passed()).isTrue();
        assertThat(call("GET", "/api/visits", "ro:ro123", null).passed())
                .as("聚合数字给朋友看没问题").isTrue();
        assertThat(call("POST", "/api/visit/ping", "ro:ro123", null).passed())
                .as("只读账号的访问也要计入统计，否则数字失真").isTrue();

        assertThat(call("GET", "/api/visits/logs", "ro:ro123", null).denied(403))
                .as("明细里有别人的 IP 和 UA").isTrue();
        assertThat(call("POST", "/api/news/1/analyze", "ro:ro123", null).denied(403))
                .as("只读账号点分析就是烧钱").isTrue();
    }

    // ---------- 令牌与 Basic 等价 ----------

    @Test
    @DisplayName("Bearer 令牌受同一套角色约束，不是「有令牌就全通」")
    void bearerTokenKeepsRole() throws Exception {
        TokenStore.Tokens guest = tokens.issue("guest", "guest");
        assertThat(callWithToken("GET", "/api/news", guest.accessToken()).passed()).isTrue();
        assertThat(callWithToken("GET", "/api/visits", guest.accessToken()).denied(403))
                .as("换了令牌也不能越权，角色来自账号而不是令牌形式").isTrue();

        TokenStore.Tokens rw = tokens.issue("admin", "rw");
        assertThat(callWithToken("GET", "/api/visits/logs", rw.accessToken()).passed()).isTrue();
    }

    @Test
    @DisplayName("乱造的令牌 401，不要回落到 Basic 分支")
    void bogusTokenIs401() throws Exception {
        assertThat(callWithToken("GET", "/api/status", "nope").denied(401)).isTrue();
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
