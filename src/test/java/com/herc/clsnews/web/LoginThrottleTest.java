package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 换令牌限流。
 *
 * <p>这个类的每一条规则都直接决定「默认口令能不能被爆破」和「正常用户会不会被误锁」，
 * 而它自己没有任何外部依赖 —— 所以断言必须写死具体数字，不能只断言「锁住了」：
 * 阈值变了、退避不生效了、锁从 IP 蔓延到全局了，都必须变红。
 *
 * <p>时间用注入的时钟推进，不 sleep：锁 15 分钟的用例不可能真等 15 分钟，
 * 而 sleep 式的测试最后都会被人改成 {@code @Disabled}。
 */
class LoginThrottleTest {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private AppProps props;

    @BeforeEach
    void setUp() {
        props = new AppProps();
        props.getSecurity().setLoginMaxFails(5);
        props.getSecurity().setLoginLock(Duration.ofMinutes(15));
        props.getSecurity().setLoginFailDelay(Duration.ofMillis(200));
        props.getSecurity().setLoginMaxDelay(Duration.ofSeconds(2));
    }

    private LoginThrottle throttle() {
        return new LoginThrottle(props, clock::get);
    }

    /** 连续失败 n 次（不看返回值），模拟脚本猛试。 */
    private void failTimes(LoginThrottle t, String ip, String user, int n) {
        for (int i = 0; i < n; i++) {
            t.recordFailure(ip, user);
            clock.addAndGet(1000);
        }
    }

    @Test
    @DisplayName("连续 5 次失败后锁定，前 4 次不锁")
    void locksAfterFifthFailure() {
        LoginThrottle t = throttle();

        failTimes(t, "1.1.1.1", "admin", 4);
        assertThat(t.check("1.1.1.1", "admin").allowed())
                .as("4 次还不到阈值，正常人打错几次不该被锁").isTrue();

        t.recordFailure("1.1.1.1", "admin");
        LoginThrottle.Decision d = t.check("1.1.1.1", "admin");
        assertThat(d.allowed()).as("第 5 次之后必须锁").isFalse();
        assertThat(d.retryAfterSec()).isBetween(890L, 900L)
                .as("Retry-After 应接近 15 分钟，不能是 0（那等于没锁）");
    }

    @Test
    @DisplayName("锁只作用于「这个 IP 上的这个账号」，不外溢")
    void lockIsScopedToIpAndUser() {
        LoginThrottle t = throttle();
        failTimes(t, "1.1.1.1", "admin", 5);
        assertThat(t.check("1.1.1.1", "admin").allowed()).isFalse();

        assertThat(t.check("1.1.1.1", "guest").allowed())
                .as("同 IP 的另一个账号不受牵连，否则一个人输错锁死全办公室").isTrue();
        assertThat(t.check("2.2.2.2", "admin").allowed())
                .as("同一账号从别的 IP 不受牵连，否则就是不用密码的 DoS").isTrue();
    }

    @Test
    @DisplayName("成功一次就把计数清零：偶尔打错不会攒到下个月")
    void successResetsCounter() {
        LoginThrottle t = throttle();
        failTimes(t, "1.1.1.1", "admin", 4);

        t.recordSuccess("1.1.1.1", "admin");
        assertThat(t.size()).as("成功之后这条记录应该被删掉").isZero();

        failTimes(t, "1.1.1.1", "admin", 4);
        assertThat(t.check("1.1.1.1", "admin").allowed())
                .as("清零后再错 4 次仍不该锁").isTrue();
    }

    @Test
    @DisplayName("失败延迟指数递增并封顶 2 秒")
    void failureDelayBacksOff() {
        LoginThrottle t = throttle();
        long[] got = new long[6];
        for (int i = 0; i < 6; i++) {
            got[i] = t.recordFailure("1.1.1.1", "admin");
            clock.addAndGet(1000);
        }
        assertThat(got).containsExactly(200, 400, 800, 1600, 2000, 2000);
    }

    @Test
    @DisplayName("锁到期自动解锁，并且计数归零（否则每 15 分钟只能试一次）")
    void lockExpiresAndResets() {
        // 用短锁测试到期：15 分钟的锁没法在用例里真的等，而改时钟又会先触发清理
        props.getSecurity().setLoginLock(Duration.ofSeconds(10));
        LoginThrottle t = throttle();

        failTimes(t, "1.1.1.1", "admin", 5);
        assertThat(t.check("1.1.1.1", "admin").allowed()).isFalse();

        clock.addAndGet(Duration.ofSeconds(11).toMillis());
        assertThat(t.check("1.1.1.1", "admin").allowed())
                .as("到期必须能再试，否则忘了密码的人等于被永久封号").isTrue();

        long delay = t.recordFailure("1.1.1.1", "admin");
        assertThat(delay).as("计数已清零，第一次失败应为最小延迟").isEqualTo(200);
        assertThat(t.check("1.1.1.1", "admin").allowed())
                .as("归零后错一次不该立刻又锁").isTrue();
    }

    @Test
    @DisplayName("锁定期间继续失败不会延长锁：避免一次误触变成永久锁定")
    void failuresDuringLockDoNotExtendIt() {
        LoginThrottle t = throttle();
        failTimes(t, "1.1.1.1", "admin", 5);
        long before = t.check("1.1.1.1", "admin").retryAfterSec();

        // 同一时刻再猛试 10 次（不推进时钟，剩余时间不应有任何变化）
        for (int i = 0; i < 10; i++) {
            t.recordFailure("1.1.1.1", "admin");
        }
        assertThat(t.check("1.1.1.1", "admin").retryAfterSec())
                .isEqualTo(before)
                .as("剩余时间只应随真实时间减少，不能被继续失败顶上去");
    }

    @Test
    @DisplayName("没有凭证的探测也要计数，否则不带密码就是免限流通道")
    void blankUserIsCountedToo() {
        LoginThrottle t = throttle();
        failTimes(t, "1.1.1.1", "", 5);
        assertThat(t.check("1.1.1.1", "").allowed()).isFalse();
        assertThat(t.check("1.1.1.1", "admin").allowed())
                .as("空用户名与真实账号是两条计数，互不覆盖").isTrue();
    }

    @Test
    @DisplayName("过期条目会被清理，不会随公网扫描无限涨")
    void staleEntriesAreSwept() {
        LoginThrottle t = throttle();
        for (int i = 0; i < 50; i++) {
            t.recordFailure("10.0.0." + i, "admin");
        }
        assertThat(t.size()).isEqualTo(50);

        // 推进到「清理窗口之外」：下一次 check 会触发一次 sweep
        clock.addAndGet(Duration.ofMinutes(16).toMillis());
        t.check("192.168.1.1", "admin");
        assertThat(t.size()).as("超过 1 分钟没动过且未锁的条目应当被清掉").isZero();
    }

    @Test
    @DisplayName("阈值配成 0 或负数时按 1 处理：宁可错一次就锁，也不能变成不限流")
    void invalidThresholdFallsBackToOne() {
        props.getSecurity().setLoginMaxFails(0);
        LoginThrottle t = throttle();
        t.recordFailure("1.1.1.1", "admin");
        assertThat(t.check("1.1.1.1", "admin").allowed()).isFalse();
    }
}
