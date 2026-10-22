package org.example.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线程池单测：钉住「有界」这件事 —— 线程上限、队列容量、命名、以及饱和时回退到调用线程（背压）。
 */
class ChatExecutorConfigTest {

    private static ChatExecutorConfig config(int core, int max, int queue) {
        ChatExecutorConfig cfg = new ChatExecutorConfig();
        cfg.coreSize = core;
        cfg.maxSize = max;
        cfg.queueCapacity = queue;
        cfg.keepAliveSeconds = 30;
        cfg.threadNamePrefix = "t-";
        return cfg;
    }

    @Test
    @DisplayName("默认配置即是有界的：core=8 / max=32 / 队列 200 / 命名 chat-sse-*")
    void boundedByDefault() throws Exception {
        ChatExecutorConfig cfg = new ChatExecutorConfig();
        ThreadPoolExecutor pool = cfg.chatExecutor();
        try {
            assertEquals(8, pool.getCorePoolSize());
            assertEquals(32, pool.getMaximumPoolSize());
            assertEquals(60, pool.getKeepAliveTime(TimeUnit.SECONDS));
            assertInstanceOf(ArrayBlockingQueue.class, pool.getQueue(), "队列必须是有界 ArrayBlockingQueue");
            assertEquals(200, pool.getQueue().remainingCapacity());
            assertInstanceOf(ChatExecutorConfig.LoggingCallerRunsPolicy.class, pool.getRejectedExecutionHandler());
            assertFalse(pool.getQueue() instanceof java.util.concurrent.SynchronousQueue, "不能再是无缓冲队列");

            String[] threadName = new String[1];
            CountDownLatch latch = new CountDownLatch(1);
            pool.execute(() -> {
                threadName[0] = Thread.currentThread().getName();
                latch.countDown();
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(threadName[0].startsWith("chat-sse-"), "线程应按前缀命名: " + threadName[0]);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("max < core 时归一化为 core，避免非法配置让线程池建不起来")
    void maxNormalized() {
        ThreadPoolExecutor pool = config(4, 2, 10).chatExecutor();
        try {
            assertEquals(4, pool.getCorePoolSize());
            assertEquals(4, pool.getMaximumPoolSize());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("饱和：队列满后任务回退到调用线程执行（不丢任务、不无限建线程）")
    void callerRunsWhenSaturated() throws Exception {
        ChatExecutorConfig cfg = config(1, 1, 1);
        ThreadPoolExecutor pool = cfg.chatExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch running = new CountDownLatch(1);
            pool.execute(() -> {
                running.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS), "第一个任务应占满唯一线程");

            pool.execute(() -> { });                       // 队列容量 1 → 进队列
            assertEquals(1, pool.getQueue().size());

            String[] ranOn = new String[1];
            pool.execute(() -> ranOn[0] = Thread.currentThread().getName()); // 队列已满 → 背压
            assertEquals(Thread.currentThread().getName(), ranOn[0],
                    "被拒绝的任务必须在调用线程执行（CallerRuns 语义）");
            assertEquals(1, cfg.rejectionPolicy().rejectedCount(), "拒绝次数应被记录");
            assertEquals(1, pool.getMaximumPoolSize(), "线程数不能因为任务多而突破上限");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("关闭后提交：不抛异常、也不执行（拒绝策略在 shutdown 时跳过任务）")
    void afterShutdownDropWithoutThrow() {
        ChatExecutorConfig cfg = config(1, 1, 1);
        ThreadPoolExecutor pool = cfg.chatExecutor();
        pool.shutdown();
        assertTrue(pool.isShutdown());

        AtomicBoolean ran = new AtomicBoolean(false);
        pool.execute(() -> ran.set(true));
        assertFalse(ran.get(), "shutdown 后任务不应执行");
        assertEquals(1, cfg.rejectionPolicy().rejectedCount());
    }
}
