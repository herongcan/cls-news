package com.herc.clsnews.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内单飞（方案 B）的测试。
 *
 * <p>为什么这段必须用真线程测：它的全部价值就是"两个线程同时进来时只执行一次"。
 * 用 mock 或者顺序调用测出来的都是假的 —— 顺序调用根本不经过竞争那条路径。
 */
class SingleFlightTest {

    private static final long WAIT_MS = 5_000;

    @Test
    @DisplayName("同键 8 个并发只执行一次，其余 7 个拿到同一份结果")
    void mergesConcurrentCalls() throws Exception {
        SingleFlight sf = new SingleFlight();
        AtomicInteger runs = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);

        Callable<SingleFlight.Waited<String>> call = () -> {
            ready.countDown();
            go.await();
            return sf.run("same-key", WAIT_MS, () -> {
                runs.incrementAndGet();
                Thread.sleep(200);   // 让所有线程都真的落在执行期间
                return "value";
            });
        };

        List<Future<SingleFlight.Waited<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(call));
        }
        ready.await();
        go.countDown();

        int followers = 0;
        for (Future<SingleFlight.Waited<String>> f : futures) {
            SingleFlight.Waited<String> w = f.get(10, TimeUnit.SECONDS);
            assertEquals("value", w.value());
            if (w.follower()) {
                followers++;
            }
        }
        pool.shutdown();

        assertEquals(1, runs.get(), "8 个并发只能执行一次任务");
        assertEquals(7, followers);
        assertEquals(0, sf.size(), "key 必须被清掉，否则内存只涨不落");
    }

    @Test
    @DisplayName("不同 key 互不阻塞：两个任务应该真正并行")
    void differentKeysRunInParallel() throws Exception {
        SingleFlight sf = new SingleFlight();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long started = System.currentTimeMillis();

        Future<String> a = pool.submit(() -> sf.run("a", WAIT_MS, () -> {
            Thread.sleep(300);
            return "A";
        }).value());
        Future<String> b = pool.submit(() -> sf.run("b", WAIT_MS, () -> {
            Thread.sleep(300);
            return "B";
        }).value());

        assertEquals("A", a.get(5, TimeUnit.SECONDS));
        assertEquals("B", b.get(5, TimeUnit.SECONDS));
        pool.shutdown();
        long cost = System.currentTimeMillis() - started;

        // 串行的话 ≥600ms。留足余量避免机器慢时假红，但仍能区分"并行"和"排队"
        assertTrue(cost < 550, "两个不同的 key 不该互相等待，实际耗时 " + cost + "ms");
    }

    @Test
    @DisplayName("leader 失败时 follower 收到同一个异常，不会自己再试一次")
    void exceptionPropagatesToFollower() throws Exception {
        SingleFlight sf = new SingleFlight();
        AtomicInteger runs = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<Object> call = () -> {
            go.await();
            return sf.run("boom", WAIT_MS, () -> {
                runs.incrementAndGet();
                Thread.sleep(150);
                throw new IllegalStateException("模型挂了");
            });
        };

        Future<Object> a = pool.submit(call);
        Future<Object> b = pool.submit(call);
        go.countDown();

        for (Future<Object> f : List.of(a, b)) {
            // leader 抛出的异常会被 Future.get 再包一层 ExecutionException，
            // follower 拿到的是一手异常 —— 两边都要落到同一个根因上
            Throwable e = assertThrows(Exception.class, () -> f.get(5, TimeUnit.SECONDS));
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            assertEquals("模型挂了", root.getMessage());
            assertTrue(root instanceof IllegalStateException, "根因类型要一致，不能变成别的异常");
        }
        pool.shutdown();

        // 关键：follower 没有自己重跑一次。重跑 = 再花一万 token 撞同一个错
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("等不到就 503 BUSY，不无限占着线程")
    void timesOutWithBusy() throws Exception {
        SingleFlight sf = new SingleFlight();
        ExecutorService pool = Executors.newSingleThreadExecutor();

        Future<Object> leader = pool.submit(() -> sf.run("slow", WAIT_MS, () -> {
            Thread.sleep(800);
            return "late";
        }));
        Thread.sleep(50);   // 让 leader 先占到 key

        AgentException e = assertThrows(AgentException.class,
                () -> sf.run("slow", 100, () -> "never"));
        assertEquals(503, e.status());
        assertEquals("BUSY", e.code());

        leader.get(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertFalse(e.getMessage().isBlank(), "给用户的话不能是空的");
    }
}
