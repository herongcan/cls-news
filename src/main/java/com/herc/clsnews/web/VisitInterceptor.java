package com.herc.clsnews.web;

import com.herc.clsnews.store.VisitStore;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 访问统计。挂在 {@code GET /} 和 {@code GET /index.html} 上，只记「打开了页面」。
 *
 * <p>为什么用 Interceptor 而不是 Filter：Filter 在鉴权之前执行，
 * 会把扫描器的 401、健康检查、每个 API 轮询全算进来。Interceptor 只对真正
 * 进入 Controller/静态资源的请求生效，也就是「已经通过鉴权、确实看到了页面」。
 *
 * <p>为什么不统计 API 请求：页面每 30 秒轮询一次，一个人开着不动一天几千次请求，
 * 那个数字衡量的是「刷新频率」而不是「有没有人来」。
 */
@Component
public class VisitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(VisitInterceptor.class);

    private final VisitStore store;

    public VisitInterceptor(VisitStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        // 欢迎页处理会把 / forward 到 /index.html，转发还会再进一次拦截器，
        // 不挡掉的话一次打开会被记两次（UV 不受影响，PV 会翻倍）
        if (req.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        if (!"GET".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        String uri = req.getRequestURI();
        if (!"/".equals(uri) && !"/index.html".equals(uri)) {
            return true;
        }

        try {
            store.record(clientIp(req), LocalDate.now(ZoneId.systemDefault()).toString());
        } catch (Exception e) {
            // 统计是附带功能：写库失败（锁竞争、磁盘满）绝不能让页面 500。
            // 但必须留日志，否则「数字一直不涨」会变成一个查不出的问题。
            log.warn("访客统计写入失败：{}", e.getMessage());
        }
        return true;
    }

    /**
     * 取真实客户端 IP。
     *
     * <p>容器部署时 source IP 是保留的（Docker 的 DNAT 在 PREROUTING 就改写目的地址，
     * 源地址不变），所以 remoteAddr 直接可用。但仍然优先读 X-Forwarded-For / X-Real-IP：
     * 以后要在前面挂 Nginx 或 CDN，remoteAddr 会变成代理机的地址，
     * 那时所有访客会被算成一个人，而且不会报错、只是数字永远不动。
     *
     * <p>XFF 是逗号分隔的链，第一段是客户端，后面是各级代理。
     */
    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return req.getRemoteAddr();
    }
}
