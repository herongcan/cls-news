package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存账号表，三档角色的唯一来源。
 *
 * <p>为什么从 {@code BasicAuthFilter} 抽出来：换 token 的那一步（{@code POST /api/auth/token}）
 * 也要校验 Basic 凭证，而它在过滤器链里已经被放行了 —— 不抽出来就得复制一份账号构造逻辑，
 * 那两处迟早会不一致（改了一处忘了另一处，症状是「登录能过但 token 换不出来」）。
 *
 * <p>仍然是内存表，不查库：账号来自环境变量，进程启动时成型，运行期不变。
 * 放数据库的唯一好处是能在线改密码，代价是一个私用工具多了一张表和一套后台，
 * 不划算 —— 改密码的方式就是改 {@code .env} 再重启。
 *
 * <p>命令行 {@code curl -u} 仍可用，但只能用于 {@code POST /api/auth/token} 换令牌 ——
 * 密码不再能直连业务接口（见 {@link BasicAuthFilter} 的类注释），
 * 所以运维排查是「换一次票，然后一直带 Bearer」。
 */
@Component
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public class AccountStore {

    private static final Logger log = LoggerFactory.getLogger(AccountStore.class);

    /**
     * 一个账号。{@code secret} 是整段 {@code user:pass} 的字节。
     *
     * <p>为什么不拆成字段分别比较：分开比时「用户名对了、密码错了」和「用户名就错了」
     * 的耗时不同，能一点点把用户名试出来。整段比 + {@link MessageDigest#isEqual}
     * 只泄露「整段对不对」。
     */
    public record Account(String user, String role, byte[] secret) { }

    private final Map<String, Account> accounts;

    public AccountStore(AppProps props) {
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
        map.put(user, account(user, pass, BasicAuthFilter.ROLE_RW));
        addList(map, cfg.getGuest(), BasicAuthFilter.ROLE_GUEST, "游客");
        addList(map, cfg.getReadOnly(), BasicAuthFilter.ROLE_RO, "只读");

        this.accounts = Map.copyOf(map);
        // 打印用户名而不是数量：账号配错（重名、漏写变量）时一眼就能看出来
        log.info("已开启访问控制：可写 {}，游客 {}，只读 {}",
                user,
                namesOf(map, BasicAuthFilter.ROLE_GUEST),
                namesOf(map, BasicAuthFilter.ROLE_RO));

        // 默认口令是已知的（写在 application.yml 与登录页上），上线不换等于把门开着。
        // 只告警不拒绝启动：这是十来个同事共用的内网工具，硬拦会让人为了「先跑起来」
        // 去改代码跳过检查，反而更糟。但要留一条显眼的日志，做安全排查时能对得上时间。
        if ("admin".equals(pass)) {
            log.warn("主账号 {} 仍在使用默认密码 admin —— 放到公网前务必用 APP_PASSWORD 覆盖", user);
        }
    }

    private static Account account(String user, String pass, String role) {
        return new Account(user, role, (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
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
            map.put(u, account(u, p, role));
        }
    }

    /**
     * 校验解码后的 {@code user:pass} 字节。
     *
     * <p>用户名本身不是秘密（登录框里看得见），按名字查表不构成额外泄露；
     * 真正不能按字节短路比较的是密码，所以比较放在 {@link MessageDigest#isEqual}。
     *
     * @param raw Basic 头 Base64 解码后的原始字节
     * @return 匹配的账号；用户名不存在、格式非法或密码不符时返回 {@code null}
     */
    public Account verifyBasic(byte[] raw) {
        int colon = -1;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == ':') { colon = i; break; }
        }
        if (colon <= 0) return null;
        String user = new String(raw, 0, colon, StandardCharsets.UTF_8);
        Account acct = accounts.get(user);
        if (acct == null) return null;
        return MessageDigest.isEqual(raw, acct.secret()) ? acct : null;
    }

    /** 按用户名取账号，只用于 token 换新时确认账号还在、角色没变。 */
    public Account find(String user) {
        return user == null ? null : accounts.get(user);
    }
}
