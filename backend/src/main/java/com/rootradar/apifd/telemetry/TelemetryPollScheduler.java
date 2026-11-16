package com.rootradar.apifd.telemetry;

import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.config.MonitoringExecutorConfig;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.monitoring.ServiceRegistry;
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
 * Dispatches telemetry polls of all enabled HTTP nodes with a telemetry endpoint every
 * {@code apifd.telemetry.poll-interval-seconds} onto the shared bounded executor (never blocks the tick,
 * one in-flight poll per node).
 */
@Component
@ConditionalOnProperty(name = "apifd.telemetry.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class TelemetryPollScheduler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPollScheduler.class);

    private final ServiceRegistry registry;
    private final TelemetryCollector collector;
    private final Executor executor;
    private final Clock clock;
    private final Duration interval;
    private final long tickMs;
    private final Map<Long, Instant> lastStart = new ConcurrentHashMap<>();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public TelemetryPollScheduler(ServiceRegistry registry, TelemetryCollector collector,
                                  @Qualifier(MonitoringExecutorConfig.BEAN_NAME) Executor executor, Clock clock,
                                  ApifdProperties props) {
        this.registry = registry;
        this.collector = collector;
        this.executor = executor;
        this.clock = clock;
        this.interval = Duration.ofSeconds(Math.max(1, props.telemetry().pollIntervalSeconds()));
        this.tickMs = props.monitoring().schedulerTickMs();
    }

    @Scheduled(fixedDelayString = "${apifd.monitoring.scheduler-tick-ms:1000}", initialDelayString = "${apifd.telemetry.initial-delay-ms:3000}")
    public void tick() {
        try {
            Instant now = clock.instant();
            var nodes = registry.findAll().stream().filter(s -> s.enabled() && s.nodeType() == NodeType.HTTP_SERVICE
                    && s.telemetryEndpoint() != null).toList();
            lastStart.keySet().retainAll(nodes.stream().map(MonitoredService::id).collect(Collectors.toSet()));
            Duration due = interval.minusMillis(tickMs / 2);
            for (MonitoredService s : nodes) {
                Instant last = lastStart.get(s.id());
                if (last != null && Duration.between(last, now).compareTo(due) < 0) continue;
                if (!inFlight.add(s.id())) continue;
                try {
                    executor.execute(() -> {
                        try {
                            collector.pollNow(s.id());
                        } catch (RuntimeException e) {
                            log.warn("Telemetry poll of {} failed unexpectedly: {}", s.name(), e.toString());
                        } finally {
                            inFlight.remove(s.id());
                        }
                    });
                    lastStart.put(s.id(), now);
                } catch (RejectedExecutionException e) {
                    inFlight.remove(s.id());
                    log.warn("Monitoring executor saturated; skipping poll of {} this tick", s.name());
                }
            }
        } catch (RuntimeException e) {
            log.error("Telemetry poll scheduler tick failed", e);
        }
    }
}
