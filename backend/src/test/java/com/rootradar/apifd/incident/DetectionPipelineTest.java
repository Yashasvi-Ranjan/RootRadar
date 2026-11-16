package com.rootradar.apifd.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rootradar.apifd.anomaly.DetectionResult;
import com.rootradar.apifd.anomaly.DetectionRuleProvider;
import com.rootradar.apifd.anomaly.DetectionRuleSet;
import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.monitoring.HealthQueryService;
import com.rootradar.apifd.monitoring.HealthSample;
import com.rootradar.apifd.telemetry.MetricsQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/** Pipeline wiring with fakes: input assembly, single-flight, failure isolation. No Spring, no database. */
class DetectionPipelineTest {
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

    private final MetricsQueryService metrics = mock(MetricsQueryService.class);
    private final HealthQueryService health = mock(HealthQueryService.class);
    private final DetectionRuleProvider rules = mock(DetectionRuleProvider.class);
    private final IncidentService incidents = mock(IncidentService.class);
    private final FakeAnomalyDetector detector = new FakeAnomalyDetector();
    private final ApifdProperties props = new ApifdProperties(null, false, null, null,
            new ApifdProperties.Detection(true, 10, 30, 10), null, null, null);
    private final DetectionPipeline pipeline = new DetectionPipeline(props, rules, metrics, health, detector,
            incidents, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void buildsBoundedDetectionInputAndHandsResultToIncidentService() {
        Instant from = NOW.minus(Duration.ofMinutes(30));
        DetectionRuleSet ruleSet = new DetectionRuleSet(List.of());
        List<HealthSample> samples = List.of(new HealthSample("payment", NOW.minusSeconds(5), false));
        when(rules.currentRules()).thenReturn(ruleSet);
        when(metrics.loadAllSeries(from, NOW)).thenReturn(List.of());
        when(health.recentSamples(from)).thenReturn(samples);
        IncidentChange change = new IncidentChange(7, IncidentChangeType.OPENED, null, IncidentStatus.DETECTED);
        when(incidents.applyDetection(any())).thenReturn(List.of(change));

        List<IncidentChange> result = pipeline.runOnce();

        assertThat(result).containsExactly(change);
        assertThat(detector.inputs).singleElement().satisfies(in -> {
            assertThat(in.evaluatedAt()).isEqualTo(NOW);
            assertThat(in.windowSeconds()).isEqualTo(10);
            assertThat(in.rules()).isSameAs(ruleSet);
            assertThat(in.healthSamples()).isEqualTo(samples);
        });
        verify(incidents).applyDetection(any(DetectionResult.class));
        assertThat(pipeline.lastRunAt()).contains(NOW);
    }

    @Test
    void failureIsSwallowedAndLastRunIsNotUpdated() {
        when(metrics.loadAllSeries(any(), any())).thenThrow(new IllegalStateException("db down"));
        assertThat(pipeline.runOnce()).isEmpty();
        assertThat(pipeline.lastRunAt()).isEmpty();
        verify(incidents, never()).applyDetection(any());
        // next tick works again
        org.mockito.Mockito.doReturn(List.of()).when(metrics).loadAllSeries(any(), any());
        pipeline.runOnce();
        verify(incidents).applyDetection(any());
    }

    @Test
    void optimisticLockConflictIsRetriedOnce() {
        when(incidents.applyDetection(any())).thenThrow(new OptimisticLockingFailureException("x"))
                .thenReturn(List.of());
        pipeline.runOnce();
        verify(incidents, times(2)).applyDetection(any());
        assertThat(pipeline.lastRunAt()).isPresent();
    }

    @Test
    void overlappingRunsAreSkipped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        detector.script = in -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new DetectionResult(in.evaluatedAt(), List.of(), List.of());
        };
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<List<IncidentChange>> first = ex.submit(pipeline::runOnce);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pipeline.runOnce()).as("second run while first is in flight").isEmpty();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
        assertThat(detector.inputs).hasSize(1);
        pipeline.runOnce(); // the guard is released afterwards
        assertThat(detector.inputs).hasSize(2);
    }

    @Test
    void scheduledRunHonoursTheEnabledFlag() {
        ApifdProperties off = new ApifdProperties(null, false, null, null,
                new ApifdProperties.Detection(false, 10, 30, 10), null, null, null);
        DetectionPipeline disabled = new DetectionPipeline(off, rules, metrics, health, detector, incidents,
                Clock.fixed(NOW, ZoneOffset.UTC));
        disabled.scheduledRun();
        assertThat(detector.inputs).isEmpty();
    }
}
