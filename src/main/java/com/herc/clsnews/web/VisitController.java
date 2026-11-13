package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import com.herc.clsnews.store.AccessLogStore;
import com.herc.clsnews.store.VisitStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * 访客上报。
 *
 * <p>替代原来的做法（用 Interceptor 统计 {@code GET /}）。原来那套的根本问题是
 * {@code index.html} 为了移动端能弹登录框而免鉴权，于是公网扫描器拿到的 200
 * 也被记成一次访客 —— 实测 79 条记录里 83% 是只来过一次的扫描器。
 *
 * <p>现在只有「拿到有效凭证的前端」才会调这个接口，扫描器过不了 BasicAuthFilter，
 * 一个字节都写不进来。
 *
 * <p>放在 {@code /api/**} 下是刻意的：不用往白名单里加任何东西，
 * 鉴权是"默认保护"而不是"默认放行再逐个补"。
 */
@RestController
@RequestMapping("/api/visit")
public class VisitController {

    private static final Logger log = LoggerFactory.getLogger(VisitController.class);

    /** 请求体。前端不带也能工作，vid 只是让人数更准一点。 */
    public record PingBody(String vid) {
    }

    private final VisitStore visits;
    private final AccessLogStore access;
    private final AppProps props;

    public VisitController(VisitStore visits, AccessLogStore access, AppProps props) {
        this.visits = visits;
        this.access = access;
        this.props = props;
    }

    /**
     * 记一次访问。由前端在 {@code start()} 里调一次，不阻塞首屏、失败不重试。
     *
     * <p>返回永远 200：统计是附带功能，它的失败不该让前端弹错误提示。
     * 但失败必须留日志，否则「数字一直不涨」会变成一个查不出的问题。
     */
    @PostMapping("/ping")
    public Map<String, Object> ping(HttpServletRequest req,
                                    @RequestBody(required = false) PingBody body) {
        boolean trustForwarded = props.getSecurity().isTrustForwardedHeaders();
        String ip = ClientIp.of(req, trustForwarded);
        String day = LocalDate.now(ZoneId.systemDefault()).toString();
        try {
            // 聚合口径（IP + 自然日去重）保持原样，页面顶栏那两个数字的含义不变
            visits.record(ip, day);
            // 明细口径：带设备、UA、vid，有 60 秒限流
            access.record(ip, body == null ? null : body.vid(),
                    req.getHeader("User-Agent"), "/", req.getHeader("Referer"));
        } catch (Exception e) {
            log.warn("访客上报失败：{}", e.getMessage());
        }
        return Map.of("ok", true);
    }
}
