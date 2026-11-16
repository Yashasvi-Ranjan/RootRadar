package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.config.MonitoringExecutorConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticks every {@code apifd.monitoring.scheduler-tick-ms}; every enabled HTTP node whose own interval has elapsed is
 * dispatched to the bounded monitoring executor. The tick itself never performs I/O, so a slow service cannot
 * delay the check of another (no head-of-line blocking); a per-service in-flight guard prevents overlapping checks
 * of the same service.
 */
@Component
@ConditionalOnProperty(name = "apifd.monitoring.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class HealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckScheduler.class);

    private final ServiceRegistry registry;
    private final HealthCheckRunner runner;
    private final Executor executor;
    private final Clock clock;
    private final long tickMs;
    private final Map<Long, Instant> lastStart = new ConcurrentHashMap<>();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public HealthCheckScheduler(ServiceRegistry registry, HealthCheckRunner runner,
                                @Qualifier(MonitoringExecutorConfig.BEAN_NAME) Executor executor, Clock clock,
                                ApifdProperties props) {
        this.registry = registry;
        this.runner = runner;
        this.executor = executor;
        this.clock = clock;
        this.tickMs = props.monitoring().schedulerTickMs();
    }

    @Scheduled(fixedDelayString = "${apifd.monitoring.scheduler-tick-ms:1000}", initialDelayString = "${apifd.monitoring.scheduler-initial-delay-ms:2000}")
    public void tick() {
        try {
            Instant now = clock.instant();
            var nodes = registry.findAll().stream()
                    .filter(s -> s.enabled() && s.nodeType() == NodeType.HTTP_SERVICE).toList();
            Set<Long> live = nodes.stream().map(MonitoredService::id).collect(Collectors.toSet());
            lastStart.keySet().retainAll(live);
            for (MonitoredService s : nodes) {
                Instant last = lastStart.get(s.id());
                Duration due = Duration.ofSeconds(s.checkIntervalSeconds()).minusMillis(tickMs / 2);
                if (last != null && Duration.between(last, now).compareTo(due) < 0) continue;
                if (!inFlight.add(s.id())) continue;
                try {
                    executor.execute(() -> {
                        try {
                            runner.runNow(s.id());
                        } catch (RuntimeException e) {
                            log.warn("Health check of {} failed unexpectedly: {}", s.name(), e.toString());
                        } finally {
                            inFlight.remove(s.id());
                        }
                    });
                    lastStart.put(s.id(), now);
                } catch (RejectedExecutionException e) {
                    inFlight.remove(s.id());
                    log.warn("Monitoring executor saturated; skipping check of {} this tick", s.name());
                }
            }
        } catch (RuntimeException e) {
            log.error("Health check scheduler tick failed", e);
        }
    }
}
