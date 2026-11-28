package com.herc.clsnews.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 进程内单飞：同一个 key 同时只允许一个任务真正执行，其余调用方等它的结果。
 *
 * <p><b>只解决"同一进程里两个请求同时点"</b>，不解决跨进程。跨进程的兜底在数据库：
 * {@code uq_brief_active} 把 {@code running} 也纳入唯一索引，第二个请求 INSERT 就失败。
 * 两层缺一不可 —— 只有数据库那层，第二个请求要么干等轮询要么 503；
 * 只有这一层，起两个副本的时候锁静默失效，而且<b>不会报任何错</b>，那是最坏的一种正确性。
 *
 * <h2>为什么用 {@link CompletableFuture} 而不是 {@code synchronized} / 每 Key 一把锁</h2>
 * 锁只能"串行化"，第二个请求拿到锁之后还得自己判断一次"是不是已经有结果了"，
 * 判断逻辑就散落到两个地方。Future 直接把"第一个的执行结果"（含异常）交给第二个，
 * 第二个请求拿到的就是同一份答案，不需要再查库、也不会重复扣费。
 *
 * <p>异常也会传播：leader 调模型失败，follower 收到同一个异常，而不是自己再试一次
 * —— 再试一次就是又花一万 token 去撞同一个错误。
 */
@Component
public class SingleFlight {

    /**
     * key → 正在执行的那次调用的 future。
     *
     * <p>值用 {@code Object} 是为了让同一张表容纳不同返回类型；调用方按 key 保证类型一致
     * （key 里含模板 id 与参数），转换只在这个类内部做一次。
     */
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    /**
     * 执行或等待。
     *
     * @param key    业务键。同键的并发调用会被合并，必须能唯一标识"同一件事"，
     *               例如 {@code template|window|watermark|params}
     * @param waitMs follower 最多等多久；超时抛 503，而不是无限占着线程
     * @param task   真正要做的事
     * @return 结果 + 自己是不是 follower（follower 的结果其实是别人算的，记账要区分）
     */
    @SuppressWarnings("unchecked")
    public <T> Waited<T> run(String key, long waitMs, Callable<T> task) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, mine);

        if (existing != null) {
            try {
                return new Waited<>((T) existing.get(waitMs, TimeUnit.MILLISECONDS), true);
            } catch (ExecutionException e) {
                // leader 失败了。把原因原样抛给 follower —— 它自己重试只会再花一次钱
                throw rethrow(e.getCause());
            } catch (TimeoutException e) {
                throw new AgentException(503, "BUSY",
                        "同一批数据正在生成中，等 " + (waitMs / 1000) + " 秒还没完成，稍后再看");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException(503, "INTERRUPTED", "等待生成结果时被中断");
            }
        }

        try {
            T value = task.call();
            mine.complete(value);
            return new Waited<>(value, false);
        } catch (Throwable t) {
            // 先把异常交给等待者，再抛给自己 —— 顺序反了的话，follower 可能永远等下去
            mine.completeExceptionally(t);
            throw rethrow(t);
        } finally {
            // 用两参 remove：万一同名 key 已经被新的一轮抢到，别把别人的 future 删掉
            inflight.remove(key, mine);
        }
    }

    /** 当前正在执行的 key 数。排查"是不是卡住了"时用。 */
    public int size() {
        return inflight.size();
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new AgentException(500, "INTERNAL", String.valueOf(t.getMessage()));
    }

    /**
     * @param value    结果
     * @param follower true = 这次没有真正执行，等的是别人的结果
     */
    public record Waited<T>(T value, boolean follower) {
    }
}
