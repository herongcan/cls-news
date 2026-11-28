package com.herc.clsnews.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全部可调参数集中在这里，对应 application.yml 的 app.* 。
 * 刻意不做成数据库配置：这个阶段改配置就该改文件、重启，够用。
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProps {

    /** cls-api | mock */
    private String source = "cls-api";
    private long intervalMs = 90_000;
    private long initialDelayMs = 1_500;

    private final Display display = new Display();
    private final Analysis analysis = new Analysis();
    private final Agent agent = new Agent();
    private final Http http = new Http();
    private final Security security = new Security();

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

    public long getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }

    public Display getDisplay() { return display; }
    public Analysis getAnalysis() { return analysis; }
    public Agent getAgent() { return agent; }
    public Http getHttp() { return http; }
    public Security getSecurity() { return security; }

    /**
     * 「今日主线」这类批量分析任务的参数。与 {@link Analysis} 分开，因为它们约束的东西不同：
     * 单条分析防的是"一条条点太多"，主线防的是"一次点下去烧掉一万 token"。
     *
     * <p>供应商相关的三项（model / base-url / api-key）留空就继承 {@link Analysis} ——
     * 同一家供应商没必要配两遍，换中转站时也不用记着改两处。
     *
     * <p>其中 <b>api-key 已废弃</b>：key 现在由 {@code LlmKey} 在启动时解析一次、全站共用，
     * 两个消费方不可能一半有一半没有。这个字段只作迁移别名保留（见 {@code LlmKey} 的说明）。
     */
    public static class Agent {
        /** 总开关。false 时所有 /api/agent/* 返回 503，不影响单条分析 */
        private boolean enabled = true;
        /**
         * 每天最多生成几次主线，按「成功的 run 行数」计。
         *
         * <p>为什么可以比单条分析的额度小这么多：一次主线约 1 万 token，
         * 相当于 20 次单条分析的花费。100 次/天是"随便点也不会失控"的量级。
         */
        private int dailyCap = 100;
        /** 游客每天能生成几次。单价高，额度就要比 guest-daily-cap（10）保守 */
        private int guestDailyCap = 5;
        /** 输入 token 硬闸，覆盖 {@link com.herc.clsnews.agent.Packer#MAX_TOKENS_IN} */
        private int maxTokensIn = 30_000;
        /**
         * 输出上限。<b>不是 3–5 条主线需要多少，而是推理模型会把额度花在思考上。</b>
         *
         * <p>实测（2026-11-27 真模型验收，138 条候选 / 1.1 万输入 token）：
         * <ul>
         *   <li>给 1200：{@code finish_reason=length}，额度全被推理吃掉，正文一字未出</li>
         *   <li>给 3000：同样截断，这次花了 1.4 万 token</li>
         *   <li>给 8000：成功，实际输出 1561（其中正文约 400，推理约 1100）</li>
         * </ul>
         * 同一输入两次的思考长度并不稳定（3000 就爆、1561 就够），所以上限要留足余量而不是卡着实际用量设。
         * {@code max_tokens} 是上限不是预扣，给 8000 不会直接多花钱 —— 失败一次才是真花钱。
         */
        private int maxTokensOut = 8_000;
        /** 比单条分析长：输入一万 token 的 prefill 本身就要几秒 */
        private int timeoutMs = 60_000;
        /**
         * running 行多久算僵尸。
         *
         * <p>必须明显大于 {@link #timeoutMs}：进程还活着、只是模型慢，这行就不该被抢走。
         * 给 180 秒 = 60 秒超时的三倍，覆盖"重试一次 + 落库"的余量。
         *
         * <p>真实需要它的场景：容器在生成途中被 kill（滚动更新、OOM），running 行留在库里。
         * 启动时的 resetStuck 能清掉大部分，但清不掉"另一个实例还活着"的那种 —— 那是 BUSY，
         * 不是僵尸，两者要靠时间戳区分。
         */
        private int staleSeconds = 180;
        /**
         * 发现别人正在生成时，最多等多久（毫秒）。
         *
         * <p>正常情况下同进程的并发已被 SingleFlight 合并，走到这里只可能是另一个实例在跑。
         * 等一等比直接 503 好：用户想要的是结果，不是"再点一次"。
         * 但也不能无限等 —— 占着 Tomcat 线程就是在耗资源。
         */
        private int busyWaitMs = 15_000;
        private double temperature = 0.2;
        /** 空 = 用 analysis.model */
        private String model = "";
        /** 空 = 用 analysis.base-url */
        private String baseUrl = "";
        /**
         * <b>已废弃</b>，只作迁移别名：配了仍然生效（否则线上会突然说没 key），但会打 WARN。
         * 换 key 请改 analysis.api-key —— key 全站只有一份，共用 {@code LlmKey} 的解析结果。
         */
        private String apiKey = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDailyCap() { return dailyCap; }
        public void setDailyCap(int dailyCap) { this.dailyCap = dailyCap; }
        public int getGuestDailyCap() { return guestDailyCap; }
        public void setGuestDailyCap(int v) { this.guestDailyCap = v; }
        public int getMaxTokensIn() { return maxTokensIn; }
        public void setMaxTokensIn(int v) { this.maxTokensIn = v; }
        public int getMaxTokensOut() { return maxTokensOut; }
        public void setMaxTokensOut(int v) { this.maxTokensOut = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int v) { this.timeoutMs = v; }
        public int getStaleSeconds() { return staleSeconds; }
        public void setStaleSeconds(int v) { this.staleSeconds = v; }
        public int getBusyWaitMs() { return busyWaitMs; }
        public void setBusyWaitMs(int v) { this.busyWaitMs = v; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double v) { this.temperature = v; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    /**
     * 访问控制。这个项目默认口径是「只自己看」，一旦放到公网就必须打开，
     * 否则任何人知道地址都能看到转贴的全文，版权风险当场兑现。
     */
    public static class Security {
        /** 关掉时完全不拦截（本机开发用）。上线必须 true */
        private boolean enabled = false;
        private String username = "admin";
        /**
         * 默认 admin（见 application.yml 的 ${APP_PASSWORD:admin}）。
         * 代码里的默认值仍留空：留空时启动直接失败，不允许「以为开了鉴权其实没密码」。
         */
        private String password = "";
        /** 健康检查不能要密码，否则 Docker/负载均衡永远判死 */
        private String[] permitPaths = {"/healthz"};
        /**
         * 只读账号，格式 {@code user:pass,user2:pass2}（环境变量 {@code APP_READONLY}）。
         *
         * <p>只能 GET/HEAD/OPTIONS，外加 {@code POST /api/visit/ping}（无副作用的访客上报）。
         * 看得到访客聚合数，看不到 {@code /api/visits/logs} 的明细。
         *
         * <p>用 String 而不是 String[] 接：空字符串绑定到数组在 Spring 里行为不稳定。
         */
        private String readOnly = "";
        /**
         * 游客账号，格式 {@code user:pass,user2:pass2}（环境变量 {@code APP_GUEST}）。
         *
         * <p>比只读多一项「分析」，比主账号少访客数据和写接口。密码公开写在登录页上，
         * 所以 {@link #guestDailyCap} 是唯一的止损线，设成 0 等于关闭游客权限。
         */
        private String guest = "";
        /** 游客账号每天能分析几条。按角色汇总，多开几个游客账号不会让额度翻倍。 */
        private int guestDailyCap = 10;
        /**
         * 是否信任 X-Forwarded-For / X-Real-IP。
         *
         * <p>默认 false，只用 {@code getRemoteAddr()}：这两个头客户端能随便伪造，
         * 不带密码就能往访客表里塞假 IP。
         *
         * <p>前面挂了反向代理或隧道时必须打开，否则 remoteAddr 全是代理机地址，
         * 所有访客被算成同一个人——不报错，只是数字永远不动。
         */
        private boolean trustForwardedHeaders = false;

        /**
         * access token 有效期。短一点：它才是最常出现在请求头里的东西，
         * 泄露后的暴露窗口就是这个值。
         */
        private java.time.Duration tokenAccessTtl = java.time.Duration.ofMinutes(30);
        /**
         * refresh token 有效期。用户多久不用就要重新输密码，由它决定。
         * 7 天是折中：足够撑过一次假期，又不至于让一台借出去的手机永久持有登录态。
         */
        private java.time.Duration tokenRefreshTtl = java.time.Duration.ofDays(7);

        /**
         * 换令牌连续失败几次后锁定。按 {@code ip|user} 组合计数，锁只作用于这个组合。
         *
         * <p>默认 5：正常人打错密码很少连错五次，而脚本五次之后就该被挡住。
         * 设成 0 或负数会被 {@code LoginThrottle} 抬到 1（等于错一次就锁）。
         */
        private int loginMaxFails = 5;
        /** 锁定时长。15 分钟是「够让攻击者放弃、又不至于让忘了密码的人等一整天」的折中。 */
        private java.time.Duration loginLock = java.time.Duration.ofMinutes(15);
        /** 第 1 次失败先等多久。之后每次翻倍，封顶见 {@link #loginMaxDelay}。 */
        private java.time.Duration loginFailDelay = java.time.Duration.ofMillis(200);
        /** 失败延迟上限。别设太大：占的是 Tomcat 工作线程。 */
        private java.time.Duration loginMaxDelay = java.time.Duration.ofSeconds(2);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String[] getPermitPaths() { return permitPaths; }
        public void setPermitPaths(String[] permitPaths) { this.permitPaths = permitPaths; }
        public String getReadOnly() { return readOnly; }
        public void setReadOnly(String readOnly) { this.readOnly = readOnly; }
        public String getGuest() { return guest; }
        public void setGuest(String guest) { this.guest = guest; }
        public int getGuestDailyCap() { return guestDailyCap; }
        public void setGuestDailyCap(int guestDailyCap) { this.guestDailyCap = guestDailyCap; }
        public boolean isTrustForwardedHeaders() { return trustForwardedHeaders; }
        public void setTrustForwardedHeaders(boolean v) { this.trustForwardedHeaders = v; }
        public java.time.Duration getTokenAccessTtl() { return tokenAccessTtl; }
        public void setTokenAccessTtl(java.time.Duration d) { this.tokenAccessTtl = d; }
        public java.time.Duration getTokenRefreshTtl() { return tokenRefreshTtl; }
        public void setTokenRefreshTtl(java.time.Duration d) { this.tokenRefreshTtl = d; }
        public int getLoginMaxFails() { return loginMaxFails; }
        public void setLoginMaxFails(int v) { this.loginMaxFails = v; }
        public java.time.Duration getLoginLock() { return loginLock; }
        public void setLoginLock(java.time.Duration d) { this.loginLock = d; }
        public java.time.Duration getLoginFailDelay() { return loginFailDelay; }
        public void setLoginFailDelay(java.time.Duration d) { this.loginFailDelay = d; }
        public java.time.Duration getLoginMaxDelay() { return loginMaxDelay; }
        public void setLoginMaxDelay(java.time.Duration d) { this.loginMaxDelay = d; }
    }

    public static class Display {
        /** true = 返回正文（只自己看）；false = 只返回摘要，用于公开口径 */
        private boolean fulltext = true;
        public boolean isFulltext() { return fulltext; }
        public void setFulltext(boolean fulltext) { this.fulltext = fulltext; }
    }

    public static class Analysis {
        /** 总开关：false 时连手动分析也拒绝，一条都不会分析 */
        private boolean enabled = false;
        /**
         * 后台自动消化 pending 队列。
         *
         * <p>默认 false：分析改由用户在页面上点「分析」触发。理由是成本 ——
         * 采集约 550 条/天，全自动等于把没人在意的一半也送去模型；而且队列从旧往新跑，
         * 首屏最新那几条反而最后才被分析，看起来像「随机漏分析」。
         * 想要老的自动行为，把它设成 true 即可，两条链路共用同一个 Service。
         */
        private boolean auto = false;
        /** stub = 空实现（调用必然失败，用于识别「还没接」）；deepseek = 真实调用 */
        private String provider = "stub";
        /** 每天最多分析多少条，防止误触把额度烧光。超限会明确报 429，不再静默跳过 */
        private int dailyCap = 300;
        /** 留空则依次回落到环境变量 DEEPSEEK_API_KEY、~/.codex/auth.json */
        private String apiKey = "";
        private String baseUrl = "https://api.deepseek.com";
        /** 实测可用：deepseek-flash / deepseek-v4-pro */
        private String model = "deepseek-flash";
        private int timeoutMs = 30_000;
        private double temperature = 0.2;
        private int maxTokens = 2000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAuto() { return auto; }
        public void setAuto(boolean auto) { this.auto = auto; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getDailyCap() { return dailyCap; }
        public void setDailyCap(int dailyCap) { this.dailyCap = dailyCap; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class Http {
        private int timeoutMs = 12_000;
        private String userAgent;
        private String referer;
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public String getReferer() { return referer; }
        public void setReferer(String referer) { this.referer = referer; }
    }
}
