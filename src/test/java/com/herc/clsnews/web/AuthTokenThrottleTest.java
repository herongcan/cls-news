package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 换令牌端点的限流**接线**。
 *
 * <p>为什么除了 {@link LoginThrottleTest} 还要这一层：那个类只证明「限流器本身算得对」，
 * 不能证明「接口真的调了它」。少写一行 {@code throttle.check}，所有单元用例照样全绿，
 * 而线上依然可以被无限重试 —— 这类「逻辑对、没接上」的洞只能从接口层断言。
 *
 * <p>失败延迟在这里被配成 0：这个类要断言的是状态码与计数，不是退避曲线
 * （那条在 {@link LoginThrottleTest} 里按具体毫秒断言），睡 2 秒只会让 CI 变慢。
 */
class AuthTokenThrottleTest {

    @TempDir
    Path dir;

    private MockMvc mvc;

    private static final String ADMIN = basic("admin", "admin");
    private static final String ADMIN_WRONG = basic("admin", "wrong");
    private static final String GUEST = basic("guest", "guest");

    @BeforeEach
    void setUp() throws Exception {
        AppProps props = new AppProps();
        props.getSecurity().setEnabled(true);
        props.getSecurity().setUsername("admin");
        props.getSecurity().setPassword("admin");
        props.getSecurity().setGuest("guest:guest");
        // 3 次即锁，免得用例写一长串请求；延迟设 0，见类注释
        props.getSecurity().setLoginMaxFails(3);
        props.getSecurity().setLoginLock(Duration.ofMinutes(15));
        props.getSecurity().setLoginFailDelay(Duration.ZERO);
        props.getSecurity().setLoginMaxDelay(Duration.ZERO);

        AccountStore accounts = new AccountStore(props);
        TokenStore tokens = new TokenStore(TestDb.create(dir), props);
        AuthController controller = new AuthController(accounts, tokens, new LoginThrottle(props), props);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("正确凭证换到令牌")
    void goodCredentialsIssueToken() throws Exception {
        String body = mvc.perform(post("/api/auth/token").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("accessToken").contains("refreshToken");
    }

    @Test
    @DisplayName("连续失败到阈值后回 429，并带 Retry-After")
    void tooManyFailuresBecomeTooManyRequests() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/token").header("Authorization", ADMIN_WRONG))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/token").header("Authorization", ADMIN_WRONG))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("锁定期间连正确的密码也 429 —— 否则锁定就是个摆设")
    void lockedOutEvenWithCorrectPassword() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/token").header("Authorization", ADMIN_WRONG))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/token").header("Authorization", ADMIN))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("锁只作用于这个 IP + 账号：同 IP 的游客、同账号的别的 IP 都能继续登")
    void lockDoesNotSpread() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/token").header("Authorization", ADMIN_WRONG))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/token").header("Authorization", ADMIN))
                .andExpect(status().isTooManyRequests());

        // 同 IP 换账号：不该被牵连（否则一个同事打错三次，全办公室都进不来）
        mvc.perform(post("/api/auth/token").header("Authorization", GUEST))
                .andExpect(status().isOk());

        // 同账号换 IP：只说明「别的 IP 没被锁」，密码错就还是 401，不能是 429
        mvc.perform(post("/api/auth/token").header("Authorization", ADMIN_WRONG)
                        .with(req -> { req.setRemoteAddr("10.9.8.7"); return req; }))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("成功一次就清零：失败两次 → 登进去 → 再错两次仍不锁")
    void successResetsFailures() throws Exception {
        mvc.perform(post("/api/auth/token").header("Authorization", ADMIN_WRONG))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/token").header("Authorization", ADMIN_WRONG))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/token").header("Authorization", ADMIN))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/token").header("Authorization", ADMIN_WRONG))
                .andExpect(status().isUnauthorized());
        // 清零后重新数，2 次不该触发阈值 3
        mvc.perform(post("/api/auth/token").header("Authorization", ADMIN_WRONG))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("不带凭证的探测也被计数，不能当免限流通道")
    void missingCredentialsAreCounted() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/token")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/token")).andExpect(status().isTooManyRequests());
    }
}
