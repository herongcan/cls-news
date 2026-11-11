package com.herc.clsnews.web;

import com.herc.clsnews.store.NewsStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。**唯一不经过鉴权的业务端点**（{@code app.security.permitPaths} 默认放行 /healthz）。
 *
 * <p>语义上只回答一个问题：<b>这个进程还能干活吗</b>。
 * 刻意<b>不</b>把「抓取失败」「模型失败」算作不健康 —— 那是业务降级，不是进程死亡。
 * 如果因为财联社改接口就让容器不断重启，只会把本地积累的数据和运行状态一起丢掉。
 * 外部依赖的失败有 {@code /api/status.lastError} 专门报告，人在那里看。
 */
@RestController
public class HealthController {

    private final NewsStore store;

    public HealthController(NewsStore store) {
        this.store = store;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean up;
        try {
            // 真读一次库：能返回说明 SQLite 文件在、连接池在、表结构在。
            // 只回一句 "ok" 的健康检查在磁盘写满或文件被删时照样是绿的，等于没检查。
            long total = store.count();
            body.put("status", "UP");
            body.put("total", total);
            up = true;
        } catch (Exception e) {
            body.put("status", "DOWN");
            body.put("error", String.valueOf(e.getMessage()));
            up = false;
        }
        body.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        // 必须给 503：Docker 的 HEALTHCHECK 与多数负载均衡只看状态码，不看 body
        return ResponseEntity.status(up ? 200 : 503).body(body);
    }
}
