package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 换令牌的失败限流。只保护 {@code POST /api/auth/token} —— 那是全站唯一校验密码的地方。
 *
 * <p>为什么必须有：默认配置是 {@code admin/admin}（见 application.yml 里的说明，
 * 这是「十来个同事共用一个固定密码」的取舍），**一个已知口令 + 无限重试**就是最直接的
 * 破解面。没有限流时，脚本一秒能试几百次；4 位数字口令几分钟就穿了。
 *
 * <p>为什么按 {@code ip|user} 组合计数，而不是单独按 IP 或单独按账号：
 * <ul>
 *   <li>只按 IP：办公室十几个人共用一个出口 IP，五个人各打错一次就把全公司锁在门外。</li>
 *   <li>只按账号：任何人故意输错五次就能把 admin 锁死，等于给了一个不用密码的 DoS。</li>
 * </ul>
 * 组合键把这两件事都避开：锁只作用于「这个 IP 上的这个账号」，别人从别的 IP 登录、
 * 同 IP 的另一个账号都不受影响。
 *
 * <p>为什么放内存而不是落库：这是**短时状态**，重启即清零——攻击者无法让服务重启，
 * 所以清零不构成绕过；而落库要为每次失败多付一次写（SQLite 单写锁），
 * 换来的只是「能看到昨天的攻击历史」，不值。
 *
 * <p>两道闸叠加，各自挡不同的东西：
 * <ol>
 *   <li><b>递增延迟</b> —— 第 n 次失败先等 {@code 200ms × 2^(n-1)}（封顶 2s）再回 401。
 *       挡的是单线程脚本：一次尝试从 1ms 变成 2s，暴力破解的时间成本直接放大三个数量级。</li>
 *   <li><b>锁定</b> —— 连续 {@code max-fails} 次失败后锁 {@code lock}（默认 15 分钟）。
 *       挡的是并发/多线程：延迟对 100 个并发连接没用（每个都只等自己那 2s）。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    /**
     * 一次校验的结果。
     *
     * @param allowed       是否允许继续校验密码
     * @param retryAfterSec 被锁时还要等几秒（{@code Retry-After} 头的值），未锁时为 0
     */
    public record Decision(boolean allowed, long retryAfterSec) {
        static final Decision OK = new Decision(true, 0);
    }

    /** 计数条目。可变，全部读写都在 {@link #entries} 的 compute 里完成。 */
    private static final class Entry {
        int fails;
        long lockUntil;
        long touchedAt;
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private final int maxFails;
    private final long lockMs;
    private final long failDelayMs;
    private final long maxDelayMs;
    private final LongSupplier now;
    /** 上次清理时间。省得每个请求都遍历一遍 Map。 */
    private volatile long lastSweep;

    /**
     * Spring 用的构造。
     *
     * <p>必须显式标 {@code @Autowired}：这个类还有第二个（包级私有的）构造给测试注入时钟用，
     * 有两个构造而没有任何一个标注解时，Spring 会去找无参构造，启动直接失败
     * （{@code No default constructor found}，实测踩过）。
     */
    @Autowired
    public LoginThrottle(AppProps props) {
        this(props, System::currentTimeMillis);
    }

    /**
     * 供测试替换时钟：锁 15 分钟的用例不能真的 sleep 15 分钟。
     */
    LoginThrottle(AppProps props, LongSupplier now) {
        AppProps.Security cfg = props.getSecurity();
        this.now = now;
        this.maxFails = Math.max(1, cfg.getLoginMaxFails());
        this.lockMs = Math.max(0, millis(cfg.getLoginLock(), Duration.ofMinutes(15)));
        this.failDelayMs = Math.max(0, millis(cfg.getLoginFailDelay(), Duration.ofMillis(200)));
        this.maxDelayMs = Math.max(failDelayMs, millis(cfg.getLoginMaxDelay(), Duration.ofSeconds(2)));
        this.lastSweep = now.getAsLong();
        log.info("换令牌限流：{} 次失败锁 {}，失败延迟上限 {}ms",
                maxFails, Duration.ofMillis(lockMs), maxDelayMs);
    }

    private static long millis(Duration d, Duration fallback) {
        return (d == null ? fallback : d).toMillis();
    }

    /**
     * 校验前先问一句：这个 IP 上的这个账号现在还能不能试。
     *
     * <p>顺带处理「锁已到期」：到期就把计数清零，让他重新有 {@code max-fails} 次机会。
     * 不清零的话，锁一到期再错一次就又锁 15 分钟 —— 忘了密码的人会被卡成「每 15 分钟只能试一次」，
     * 体验上等于永久封号。
     */
    public Decision check(String ip, String user) {
        long t = now.getAsLong();
        sweepIfDue(t);

        Entry e = entries.get(key(ip, user));
        if (e == null) return Decision.OK;

        synchronized (e) {
            if (e.lockUntil == 0) return Decision.OK;
            if (e.lockUntil > t) {
                return new Decision(false, Math.max(1, (e.lockUntil - t + 999) / 1000));
            }
            // 锁到期：清空重来
            e.lockUntil = 0;
            e.fails = 0;
            e.touchedAt = t;
            return Decision.OK;
        }
    }

    /**
     * 记一次失败。
     *
     * @return 这次响应前应该等多久（毫秒）。调用方负责真的去睡 —— 让这个类保持无阻塞，
     *         测试才好断言「第几次失败该等多少」。
     */
    public long recordFailure(String ip, String user) {
        long t = now.getAsLong();
        String k = key(ip, user);

        Entry e = entries.computeIfAbsent(k, x -> new Entry());
        long delay;
        boolean locked = false;
        int fails;
        synchronized (e) {
            e.fails++;
            e.touchedAt = t;
            fails = e.fails;
            if (lockMs > 0 && e.fails >= maxFails && e.lockUntil <= t) {
                e.lockUntil = t + lockMs;
                locked = true;
            }
            delay = backoff(fails);
        }

        if (locked) {
            // 这一条是运维最需要的线索：突然出现大量「换令牌失败」，就是有人在试口令
            log.warn("换令牌连续失败 {} 次，锁定 {}s：ip={} user={}",
                    fails, lockMs / 1000, ip, user);
        }
        return delay;
    }

    /** 成功一次就把这个组合的计数抹掉：正常用户偶尔打错一次，不该攒到下个月。 */
    public void recordSuccess(String ip, String user) {
        entries.remove(key(ip, user));
    }

    /** 第 n 次失败的等待时间：200、400、800、1600、2000、2000… */
    private long backoff(int fails) {
        if (failDelayMs <= 0) return 0;
        int shift = Math.min(fails - 1, 20);
        long d = failDelayMs << shift;
        return d <= 0 || d > maxDelayMs ? maxDelayMs : d;
    }

    /**
     * 每分钟最多清一次过期条目。
     *
     * <p>为什么要清：锁一旦过期，条目就没用了；但如果没人访问它，它也不会自己消失。
     * 一个公网 IP 每分钟换一个来试，一天就是 1440 条 —— 不清理的话这是个慢性内存泄漏。
     */
    private void sweepIfDue(long t) {
        if (t - lastSweep < 60_000) return;
        lastSweep = t;
        long staleBefore = t - Math.max(lockMs, 60_000);
        entries.entrySet().removeIf(en -> {
            Entry e = en.getValue();
            synchronized (e) {
                return e.lockUntil <= t && e.touchedAt < staleBefore;
            }
        });
    }

    /**
     * 组合键。user 用空串占位（缺凭证的探测也要被计数，否则「不带密码」就成了免限流的探测通道）。
     *
     * <p>用 {@code \u0000} 分隔而不是冒号：用户名里理论上可以有冒号，分隔符撞了会让
     * 两个不同的组合落到同一个键上；NUL 在 HTTP 头里不可能出现。
     */
    private static String key(String ip, String user) {
        return (ip == null ? "" : ip) + '\u0000' + (user == null ? "" : user);
    }

    /** 仅供测试与排查：当前有多少条计数。 */
    int size() {
        return entries.size();
    }
}
