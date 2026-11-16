package com.rootradar.apifd.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounded executor shared by the health-check scheduler and the telemetry collector.
 * Fixed upper bound on threads ({@code apifd.monitoring.max-concurrent-checks}) and a bounded queue, so memory and
 * concurrency stay bounded; when the queue is full submissions are rejected (the scheduler logs and retries next tick).
 * One slow or hanging service occupies at most one thread (per-service in-flight guard) and only until its timeout.
 */
@Configuration
public class MonitoringExecutorConfig {

    public static final String BEAN_NAME = "monitoringExecutor";
    static final int QUEUE_CAPACITY = 256;

    @Bean(name = BEAN_NAME, destroyMethod = "shutdownNow")
    public ThreadPoolExecutor monitoringExecutor(ApifdProperties props) {
        int threads = Math.max(1, props.monitoring().maxConcurrentChecks());
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "apifd-monitor-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor ex = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), tf, new ThreadPoolExecutor.AbortPolicy());
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }
}
