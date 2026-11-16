package com.rootradar.apifd.incident;

import com.rootradar.apifd.anomaly.AnomalyDetector;
import com.rootradar.apifd.anomaly.DetectionInput;
import com.rootradar.apifd.anomaly.DetectionResult;
import com.rootradar.apifd.anomaly.DetectionRuleProvider;
import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.monitoring.HealthQueryService;
import com.rootradar.apifd.telemetry.MetricsQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled detection runner (docs/ARCHITECTURE.md section 3). Loads persisted telemetry for a bounded look-back,
 * runs the pure {@link AnomalyDetector} and hands the result to {@link IncidentService}. Single-flight: a run that
 * is still in progress makes the next tick a no-op. Failures are logged and never propagate to the scheduler.
 */
@Component
public class DetectionPipeline {
    private static final Logger log = LoggerFactory.getLogger(DetectionPipeline.class);

    private final ApifdProperties props;
    private final DetectionRuleProvider rules;
    private final MetricsQueryService metrics;
    private final HealthQueryService health;
    private final AnomalyDetector detector;
    private final IncidentService incidents;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>();

    public DetectionPipeline(ApifdProperties props, DetectionRuleProvider rules, MetricsQueryService metrics,
                             HealthQueryService health, AnomalyDetector detector, IncidentService incidents,
                             Clock clock) {
        this.props = props;
        this.rules = rules;
        this.metrics = metrics;
        this.health = health;
        this.detector = detector;
        this.incidents = incidents;
        this.clock = clock;
    }

    /** Scheduler entry. Honours {@code apifd.detection.enabled}. */
    @Scheduled(initialDelayString = "#{${apifd.detection.interval-seconds:10} * 1000}",
            fixedDelayString = "#{${apifd.detection.interval-seconds:10} * 1000}")
    void scheduledRun() {
        if (!props.detection().enabled()) return;
        runOnce();
    }

    /**
     * One detection cycle. Safe to call from tests.
     * @return the incident changes made; empty if nothing changed, a run was already in flight, or the run failed
     */
    public List<IncidentChange> runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Detection run skipped: previous run still in progress");
            return List.of();
        }
        try {
            Instant now = clock.instant();
            Instant from = now.minus(Duration.ofMinutes(props.detection().lookbackMinutes()));
            DetectionInput input = new DetectionInput(now, props.detection().windowSeconds(),
                    metrics.loadAllSeries(from, now), health.recentSamples(from), rules.currentRules());
            DetectionResult result = detector.detect(input);
            List<IncidentChange> changes;
            try {
                changes = incidents.applyDetection(result);
            } catch (OptimisticLockingFailureException ex) {
                log.warn("Concurrent incident update, retrying once");
                changes = incidents.applyDetection(result);
            }
            lastRunAt.set(now);
            if (!changes.isEmpty()) log.info("Detection run applied {} incident change(s)", changes.size());
            return changes;
        } catch (RuntimeException ex) {
            log.error("Detection run failed; will retry on the next tick", ex);
            return List.of();
        } finally {
            running.set(false);
        }
    }

    /** Completion time (clock) of the last successful run, empty before the first one. */
    public Optional<Instant> lastRunAt() {
        return Optional.ofNullable(lastRunAt.get());
    }
}
