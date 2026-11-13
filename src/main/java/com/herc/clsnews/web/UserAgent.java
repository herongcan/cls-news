package com.herc.clsnews.web;

/**
 * 从 User-Agent 里粗分设备 / 系统 / 浏览器。
 *
 * <p>为什么手写而不是引 uap-java：那个库要拉 2MB 的正则表，而这里的访问者
 * 只有 iPhone、安卓、微信内置浏览器、Mac/Windows 上的 Chrome 这四五种组合。
 * 识别率的提升换不来一个额外依赖和一份启动开销。
 *
 * <p>判断顺序是这个类唯一的难点，两条都不能颠倒：
 *
 * <ol>
 *   <li><b>bot 必须最先判。</b> 公网扫描器（Censys、Shodan、zgrab）的 UA 里
 *       往往带 "Chrome"，按常规顺序会先被识别成真人。</li>
 *   <li><b>微信/企微必须早于 Chrome 和 Safari。</b> 微信内置浏览器的 UA 同时含
 *       "MicroMessenger"、"Chrome"、"Safari"，顺序反了就永远是 Chrome ——
 *       而这批人恰好是移动端的主要来源。</li>
 * </ol>
 */
public final class UserAgent {

    private UserAgent() {
    }

    /** 解析结果。三个字段永远非空，识别不出时是 "unknown"。 */
    public record Info(String device, String os, String browser) {
    }

    private static final Info UNKNOWN = new Info("unknown", "unknown", "unknown");

    /**
     * 命中即判为爬虫。
     *
     * <p>清单里前几个是实测会打到你端口上的：Censys 的 UA 是
     * {@code Mozilla/5.0 (compatible; CensysInspect/1.1; ...) Chrome/120} ——
     * 它**故意**带 Chrome 字样，只按常规顺序判断会认成"桌面版 Chrome"。
     * 这也是为什么判 bot 必须是第一步，而不是最后兜底。
     *
     * <p>这里刻意不写裸的 {@code "bot"}：CUBOT 手机的 UA 里就含 "CUBOT"，
     * 一个 contains 就会把真人误判成爬虫。改成 {@code "bot/"}、{@code "bot;"}
     * 加具名列表 —— 覆盖 "*bot/2.1" 这类通用形态，又不碰品牌名。
     */
    private static final String[] BOT_MARKS = {
            "bot/", "bot;", "crawler", "spider", "curl/", "wget/", "python-requests",
            "headless", "zgrab", "zmap", "scan", "monitor", "uptime", "httpclient",
            "censys", "shodan", "nmap", "masscan", "nuclei", "nikto", "sqlmap",
            "acunetix", "nessus", "netsparker", "gobuster", "dirbuster",
            "internet-measurement", "netcraft", "leakix",
            "googlebot", "bingbot", "yandexbot", "duckduckbot", "baiduspider",
            "bytespider", "petalbot", "applebot", "ahrefsbot", "semrushbot",
            "mj12bot", "dotbot", "facebookexternalhit"
    };

    public static Info parse(String ua) {
        if (ua == null || ua.isBlank()) {
            return UNKNOWN;
        }
        String s = ua.toLowerCase();

        if (isBot(s)) {
            return new Info("bot", "bot", "bot");
        }

        String device = device(s);
        return new Info(device, os(s), browser(s, device));
    }

    private static boolean isBot(String s) {
        for (String m : BOT_MARKS) {
            if (s.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private static String device(String s) {
        if (s.contains("micromessenger")) {
            return "wechat";
        }
        if (s.contains("wxwork")) {
            return "wecom";
        }
        // QQ 内置浏览器的 UA 尾巴是 " QQ/8.x"，加斜杠避免命中普通字符串
        if (s.contains(" qq/")) {
            return "qq";
        }
        if (s.contains("ipad") || s.contains("tablet")) {
            return "tablet";
        }
        if (s.contains("mobile") || s.contains("android") || s.contains("iphone")) {
            return "mobile";
        }
        return "desktop";
    }

    private static String os(String s) {
        // iOS 必须在 macOS 之前判：iPad 的 UA 里有 "Macintosh"
        if (s.contains("iphone") || s.contains("ipad") || s.contains("ios")) {
            return "ios";
        }
        if (s.contains("android")) {
            return "android";
        }
        if (s.contains("windows")) {
            return "windows";
        }
        if (s.contains("mac os") || s.contains("macintosh")) {
            return "macos";
        }
        if (s.contains("linux")) {
            return "linux";
        }
        return "unknown";
    }

    private static String browser(String s, String device) {
        // 微信用的是自己内核，报 Chrome 只是兼容标记，对外统一说 wechat
        if ("wechat".equals(device) || "wecom".equals(device) || "qq".equals(device)) {
            return "wechat";
        }
        // 从专到泛：Edge 的 UA 含 Chrome，Chrome 的含 Safari
        if (s.contains("edg/") || s.contains("edga/") || s.contains("edgios/")) {
            return "edge";
        }
        if (s.contains("firefox")) {
            return "firefox";
        }
        if (s.contains("chrome") || s.contains("crios")) {
            return "chrome";
        }
        if (s.contains("safari")) {
            return "safari";
        }
        return "unknown";
    }
}
