package com.herc.clsnews.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取真实客户端 IP。
 *
 * <p>默认只看 {@code getRemoteAddr()}，不读 X-Forwarded-For：XFF 客户端可以任意写，
 * 没挂代理时会被人用一行 curl 往访客表里塞假 IP。前面有代理或隧道时，
 * 用 {@code app.security.trust-forwarded-headers} 显式打开。
 *
 * <p>容器部署时源地址是保留的（Docker DNAT 只改目的地址），compose 下 remoteAddr 直接可用。
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
