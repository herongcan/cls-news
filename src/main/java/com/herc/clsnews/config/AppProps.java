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
    public Http getHttp() { return http; }
    public Security getSecurity() { return security; }

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
