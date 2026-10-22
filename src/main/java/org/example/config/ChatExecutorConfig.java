package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 对话线程池：有界线程 + 有界队列 + 背压。
 *
 * <p>改造前是 `Executors.newCachedThreadPool()`：</p>
 * <ul>
 *   <li>**线程数无上限**（maximumPoolSize = Integer.MAX_VALUE）；</li>
 *   <li>队列是 `SynchronousQueue`（容量 0，不缓冲）；</li>
 *   <li>结果：突发请求时无限创建线程 → 上下文切换爆炸、栈内存耗尽，最终 OOM 或整机不可用；</li>
 *   <li>而且饱和时没有任何信号：线程池不会拒绝、不会告警，只会继续建线程。</li>
 * </ul>
 *
 * <p>现在换成：固定上限的线程 + 固定容量的 `ArrayBlockingQueue` + 自定义拒绝策略
 * （限频告警 + 回退到调用线程执行，即 CallerRuns 背压），并给线程命名、挂未捕获异常处理器，
 * 出问题能在日志里定位是哪个线程。</p>
 */
@Configuration
public class ChatExecutorConfig {

    private static final Logger logger = LoggerFactory.getLogger(ChatExecutorConfig.class);

    /** 常驻线程数：任务是"构建 Agent + 发起订阅"（短任务），8 个足够应付稳态 */
    @Value("${chat.executor.core-size:8}")
    int coreSize = 8;

    /** 峰值线程上限：突发流量最多扩张到这里，再多就排队 */
    @Value("${chat.executor.max-size:32}")
    int maxSize = 32;

    /** 队列容量：缓冲突发；打满即触发背压（回退到调用线程） */
    @Value("${chat.executor.queue-capacity:200}")
    int queueCapacity = 200;

    /** 非核心线程空闲存活时间 */
    @Value("${chat.executor.keep-alive-seconds:60}")
    long keepAliveSeconds = 60;

    @Value("${chat.executor.thread-name-prefix:chat-sse-}")
    String threadNamePrefix = "chat-sse-";

    /** 拒绝策略实例（暴露出来便于监控计数与单测断言） */
    private final LoggingCallerRunsPolicy rejectionPolicy = new LoggingCallerRunsPolicy();

    @Bean(name = "chatExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor chatExecutor() {
        int core = Math.max(1, coreSize);
        int max = Math.max(core, maxSize);
        int queue = Math.max(1, queueCapacity);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                core, max, keepAliveSeconds, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                namedThreadFactory(),
                rejectionPolicy);
        pool.allowCoreThreadTimeOut(false);
        logger.info("chat 线程池初始化: core={} max={} queue={} keepAlive={}s 拒绝策略={}",
                core, max, queue, keepAliveSeconds, rejectionPolicy.getClass().getSimpleName());
        return pool;
    }

    /** 供监控/测试读取 */
    public LoggingCallerRunsPolicy rejectionPolicy() {
        return rejectionPolicy;
    }

    /** 线程命名 + 未捕获异常处理：日志里能看出是 chat 线程池的第几个线程 */
    private ThreadFactory namedThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, threadNamePrefix + seq.incrementAndGet());
            thread.setDaemon(false);
            thread.setUncaughtExceptionHandler((t, ex) ->
                    logger.error("chat 线程池任务未捕获异常: thread={}", t.getName(), ex));
            return thread;
        };
    }

    /**
     * 饱和策略：限频告警 + **回退到调用线程执行**。
     *
     * <p>不用 AbortPolicy：SSE 请求被直接拒绝对用户来说是"莫名失败"；
     * 不用 DiscardPolicy：任务静默丢失更难查。
     * CallerRuns 的语义是背压 —— 线程池满了就让提交者（Tomcat 请求线程）自己跑，
     * 压力自然回传到入口，不会无限堆积。</p>
     */
    public static final class LoggingCallerRunsPolicy implements RejectedExecutionHandler {

        private static final Logger log = LoggerFactory.getLogger(LoggingCallerRunsPolicy.class);

        private final AtomicLong rejected = new AtomicLong();

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            long n = rejected.incrementAndGet();
            // 限频：首次与每 100 次打一条，避免饱和时日志刷屏
            if (n == 1 || n % 100 == 0) {
                log.warn("chat 线程池饱和：累计拒绝 {} 次，任务回退到调用线程执行 "
                                + "(pool={} active={} queue={} max={})",
                        n, e.getPoolSize(), e.getActiveCount(), e.getQueue().size(), e.getMaximumPoolSize());
            }
            if (!e.isShutdown()) {
                r.run();
            }
        }

        public long rejectedCount() {
            return rejected.get();
        }
    }
}
