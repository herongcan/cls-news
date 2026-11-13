package com.herc.clsnews.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取真实客户端 IP。
 *
 * <p>默认只看 {@code getRemoteAddr()}，不读 X-Forwarded-For。这是一个刻意的收紧：
 *
 * <p>之前的实现优先读 XFF，注释里的理由是「以后挂代理时 remoteAddr 会变成代理机地址」。
 * 方向是对的，但代价被忽略了 —— XFF 是客户端可以任意写的请求头。在没有任何反向代理的
 * 现状下，随便一句
 *
 * <pre>{@code curl -H "X-Forwarded-For: 8.8.8.8" http://IP:8080/api/visit/ping}</pre>
 *
 * <p>就能往访客表里塞一个不存在的 IP。所以改成「默认不信，挂了代理再显式打开」，
 * 由 {@code app.security.trust-forwarded-headers} 控制。
 *
 * <p>容器部署时源地址是保留的（Docker 的 DNAT 在 PREROUTING 改写目的地址，源地址不变），
 * 所以 remoteAddr 在 compose 下直接可用，不需要额外配置。
 */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * @param req            当前请求
     * @param trustForwarded 前面是否确实有反向代理在写这两个头
     * @return 客户端 IP，取不到时返回空串（调用方按「不计入」处理）
     */
    public static String of(HttpServletRequest req, boolean trustForwarded) {
        if (trustForwarded) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // XFF 是逗号分隔的链，最左一段是客户端，后面是各级代理
                int comma = xff.indexOf(',');
                return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
            }
            String real = req.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
        }
        String remote = req.getRemoteAddr();
        return remote == null ? "" : remote;
    }
}
