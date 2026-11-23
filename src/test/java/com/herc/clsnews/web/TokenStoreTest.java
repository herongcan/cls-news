package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌落库后的行为。
 *
 * <p>这套用例的起点是一个真实故障：原来令牌存在内存里，服务一重启（改配置、部署、
 * 容器滚动更新都算）全站掉线，用户侧看到的是「刷着刷着突然要重新登录」。
 * 第一个用例就是把这个场景钉死 —— 换一个 TokenStore 实例（= 进程重启，内存全丢）
 * 之后，重启前签发的令牌必须照样能用。
 *
 * <p>其余几条覆盖旋转、多标签宽限、登出隔离、过期与「原文不落库」。
 * 这些都是改一行 SQL 就可能悄悄坏掉、而界面上不会立刻报错的地方。
 */
class TokenStoreTest {

    @TempDir
    Path dir;

    private JdbcTemplate jdbc;
    private AppProps props;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.create(dir);
        props = props(Duration.ofMinutes(30), Duration.ofDays(7));
    }

    private AppProps props(Duration access, Duration refresh) {
        AppProps p = new AppProps();
        p.getSecurity().setEnabled(true);
        p.getSecurity().setTokenAccessTtl(access);
        p.getSecurity().setTokenRefreshTtl(refresh);
        return p;
    }

    private int rows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM auth_token", Integer.class);
        return n == null ? 0 : n;
    }

    // ---------- 重启不失效（这次故障的核心） ----------

    @Test
    @DisplayName("进程重启后，重启前签发的令牌仍然有效")
    void tokensSurviveRestart() {
        TokenStore before = new TokenStore(jdbc, props);
        TokenStore.Tokens t = before.issue("admin", "rw");

        // 新实例 = 新进程：内存里一个字段都没带过来，只剩库
        TokenStore after = new TokenStore(jdbc, props);

        TokenStore.Principal p = after.resolveAccess(t.accessToken());
        assertThat(p).as("重启不该让所有人掉线").isNotNull();
        assertThat(p.user()).isEqualTo("admin");
        assertThat(p.role()).isEqualTo("rw");

        // refresh 也要能过：只保住 access 的话，30 分钟后照样掉线，等于没修
        TokenStore.Tokens next = after.refresh(t.refreshToken());
        assertThat(next).isNotNull();
        assertThat(next.accessToken()).isNotEqualTo(t.accessToken());
    }

    // ---------- 旋转与多标签宽限 ----------

    @Test
    @DisplayName("刷新换新对，新 access 立即可用")
    void refreshIssuesUsablePair() {
        TokenStore s = new TokenStore(jdbc, props);
        TokenStore.Tokens t = s.issue("admin", "rw");

        TokenStore.Tokens next = s.refresh(t.refreshToken());
        assertThat(s.resolveAccess(next.accessToken())).isNotNull();
        assertThat(next.refreshToken()).isNotEqualTo(t.refreshToken());
    }

    @Test
    @DisplayName("旧 refresh 在宽限窗口内仍可用：两个标签同时刷新不会互相踢下线")
    void rotatedRefreshStillWorksWithinGrace() {
        TokenStore s = new TokenStore(jdbc, props);
        TokenStore.Tokens t = s.issue("admin", "rw");

        // 标签 A 先刷新成功
        assertThat(s.refresh(t.refreshToken())).isNotNull();
        // 标签 B 手里还是同一个 refresh（它读到之后 A 才写入），不能因此被登出
        assertThat(s.refresh(t.refreshToken()))
                .as("严格的一次性旋转在多标签下会把自己人踢掉").isNotNull();
    }

    @Test
    @DisplayName("宽限窗口不会被重复使用反复延长")
    void graceWindowDoesNotSlide() {
        TokenStore s = new TokenStore(jdbc, props);
        TokenStore.Tokens t = s.issue("admin", "rw");
        s.refresh(t.refreshToken());

        Long before = jdbc.queryForObject("SELECT MIN(expires_at) FROM auth_token WHERE kind='refresh'", Long.class);
        s.refresh(t.refreshToken());
        Long after = jdbc.queryForObject("SELECT MIN(expires_at) FROM auth_token WHERE kind='refresh'", Long.class);

        assertThat(after)
                .as("每次重放都在续命的话，一个泄露的 refresh 能靠定时重放永久活着")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("过了宽限窗口的旧 refresh 失效")
    void expiredRefreshIsRejected() {
        TokenStore s = new TokenStore(jdbc, props);
        TokenStore.Tokens t = s.issue("admin", "rw");
        // 直接把这张表压到过去，等价于「30 秒已经过去了」
        jdbc.update("UPDATE auth_token SET expires_at = 0 WHERE kind = 'refresh'");

        assertThat(s.refresh(t.refreshToken())).isNull();
    }

    // ---------- 登出 ----------

    @Test
    @DisplayName("登出按 session 清干净，且不误踢同一个人另一台设备")
    void revokeKillsOnlyThatSession() {
        TokenStore s = new TokenStore(jdbc, props);
        TokenStore.Tokens phone = s.issue("admin", "rw");
        TokenStore.Tokens laptop = s.issue("admin", "rw");

        assertThat(s.revoke(phone.refreshToken())).isPositive();

        assertThat(s.resolveAccess(phone.accessToken()))
                .as("只删 refresh 的话，手里那个 access 还能再用满一个 TTL").isNull();
        assertThat(s.refresh(phone.refreshToken())).isNull();

        assertThat(s.resolveAccess(laptop.accessToken()))
                .as("不能按 username 删，否则另一台设备会被顺带踢掉").isNotNull();
        assertThat(s.refresh(laptop.refreshToken())).isNotNull();
    }

    @Test
    @DisplayName("已旋转过的旧 refresh 也能用来登出（用户手里拿的是哪一份都不重要）")
    void revokeAcceptsRotatedRefresh() {
        TokenStore s = new TokenStore(jdbc, props);
        TokenStore.Tokens t = s.issue("admin", "rw");
        TokenStore.Tokens next = s.refresh(t.refreshToken());

        s.revoke(t.refreshToken());

        assertThat(s.resolveAccess(next.accessToken())).isNull();
    }

    // ---------- 过期 ----------

    @Test
    @DisplayName("access 过期后拒绝，并把那行惰性删掉")
    void expiredAccessIsRejectedAndCleaned() {
        TokenStore s = new TokenStore(jdbc, props(Duration.ZERO, Duration.ofDays(7)));
        TokenStore.Tokens t = s.issue("admin", "rw");

        assertThat(s.resolveAccess(t.accessToken())).isNull();
        Integer left = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_token WHERE kind = 'access'", Integer.class);
        assertThat(left).isZero();
    }

    // ---------- 不该发生的事 ----------

    @Test
    @DisplayName("令牌原文不落库，只存哈希")
    void plaintextTokensAreNotStored() {
        TokenStore s = new TokenStore(jdbc, props);
        TokenStore.Tokens t = s.issue("admin", "rw");

        List<String> keys = jdbc.queryForList("SELECT token_hash FROM auth_token", String.class);
        assertThat(keys)
                .as("库文件会被备份、被拷走排查问题，原文落库等于把登录凭据一起备份出去")
                .doesNotContain(t.accessToken(), t.refreshToken());
        // Base64Url 编码的 SHA-256 去掉 padding = 43 字符
        assertThat(keys).allMatch(k -> k.length() == 43);
    }

    @Test
    @DisplayName("伪造的令牌不写库（否则随便扫一下就能让表膨胀）")
    void bogusTokenDoesNotWrite() {
        TokenStore s = new TokenStore(jdbc, props);
        s.issue("admin", "rw");
        int before = rows();

        assertThat(s.resolveAccess("nope")).isNull();
        assertThat(s.resolveAccess("")).isNull();
        assertThat(s.resolveAccess(null)).isNull();

        assertThat(rows()).isEqualTo(before);
    }
}
