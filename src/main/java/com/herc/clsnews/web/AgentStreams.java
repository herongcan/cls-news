package com.herc.clsnews.web;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE 的生成线程池与保活心跳。
 *
 * <p><b>为什么生成不跑在请求线程上</b>：{@code SseEmitter} 的语义是"先把响应头交出去、之后异步写"。
 * 在请求线程里等模型，那 14 秒会一直占着 Tomcat 的工作线程 —— 几十个人同时点就把池子耗光，
 * 而这些人并不是"连接很多"，只是"都在等同一个模型"。生成挪到自己的池里之后，
 * 请求线程发完头立刻释放。
 *
 * <p><b>池子只开 8 个</b>：主线一天上限 50 次，真实并发到 8 已经不正常了。
 * 开更大只会把"排队"这件事藏起来 —— 排队时用户看到的是流已建立、步骤迟迟不来，
 * 反而比直接排队更让人以为坏了。
 *
 * <p><b>为什么要心跳</b>：模型那一步可能十几秒没有任何数据。中间的 Nginx / 云负载均衡
 * 普遍按"多久没收到字节"判定空闲（默认 60s 一档），一旦被掐断，前端看到的是流莫名其妙结束。
 * 心跳发的是 SSE 注释行（{@code : ping}），不产生事件，解析器直接忽略。
 */
@Component
public class AgentStreams {

    private final ExecutorService workers = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "agent-stream");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService pinger = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "agent-ping");
        t.setDaemon(true);
        return t;
    });

    public void submit(Runnable task) {
        workers.submit(task);
    }

    /**
     * 每 15 秒打一次心跳。
     *
     * @return 必须在流结束时 cancel —— 不取消的话，连接早已关闭，定时任务会一直跑着往一个
     *         死掉的 emitter 里写，而且每个请求泄漏一个任务，一天下来能攒出几百个空转线程
     */
    public ScheduledFuture<?> ping(Runnable beat) {
        return pinger.scheduleAtFixedRate(beat, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        pinger.shutdownNow();
    }
}
