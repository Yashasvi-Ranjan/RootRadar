package com.rootradar.apifd.anomaly.engine;

import static com.rootradar.apifd.anomaly.engine.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.anomaly.AnomalyType;
import com.rootradar.apifd.anomaly.DetectionInput;
import com.rootradar.apifd.anomaly.DetectionMethod;
import com.rootradar.apifd.anomaly.DetectionResult;
import com.rootradar.apifd.anomaly.SeriesAssessment;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.monitoring.HealthSample;
import com.rootradar.apifd.telemetry.MetricScope;
import com.rootradar.apifd.telemetry.MetricSeries;
import com.rootradar.apifd.telemetry.SeriesKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StatisticalAnomalyDetectorTest {

    // ---------------------------------------------------------------- latency

    @Test
    void healthySeriesProducesNoAnomalyAndNormalState() {
        DetectionResult r = run(b().normal(60).build(PAYMENT));
        assertThat(r.anomalies()).isEmpty();
        SeriesAssessment a = assessmentOf(r, PAYMENT);
        assertThat(a.state()).isEqualTo(SeriesState.NORMAL);
        assertThat(a.baselineSampleCount()).isGreaterThanOrEqualTo(12);
        assertThat(a.consecutiveBreachWindows()).isZero();
        assertThat(a.consecutiveNormalWindows()).isEqualTo(60);
        assertThat(a.baselineLatencyP95Ms()).isBetween(50.0, 54.0);
    }

    @Test
    void sustainedHighLatencyIsCriticalWithEvidence() {
        DetectionResult r = run(b().normal(50).latency(3, 2000).build(PAYMENT));
        AnomalyEvent e = only(r);
        assertThat(e.type()).isEqualTo(AnomalyType.LATENCY);
        assertThat(e.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(e.detectionMethod()).isEqualTo(DetectionMethod.STATIC_THRESHOLD);
        assertThat(e.subjectService()).isEqualTo("payment-service");
        assertThat(e.fingerprint()).isEqualTo(AnomalyEvent.fingerprintOf(AnomalyType.LATENCY, PAYMENT));
        assertThat(e.observedValue()).isEqualTo(2000.0);
        assertThat(e.thresholdValue()).isEqualTo(1500.0);
        assertThat(e.baselineValue()).isBetween(50.0, 54.0);
        assertThat(e.deviationScore()).isGreaterThan(30.0);
        assertThat(e.consecutiveBreaches()).isEqualTo(3);
        assertThat(e.firstBreachAt()).isEqualTo(T.minusSeconds(30));
        assertThat(e.windowEnd()).isEqualTo(T);
        assertThat(e.detectedAt()).isEqualTo(T);
        assertThat(e.description()).contains("2000 ms").contains("critical threshold 1500 ms")
                .contains("3 consecutive windows").contains("baseline median");
        assertThat(e.details()).containsKeys("baselineMedian", "baselineMad", "ruleKey", "robustZ");
        SeriesAssessment a = assessmentOf(r, PAYMENT);
        assertThat(a.state()).isEqualTo(SeriesState.ANOMALOUS);
        assertThat(a.firstAbnormalAt()).isEqualTo(e.firstBreachAt());
        assertThat(a.currentLatencyP95Ms()).isEqualTo(2000.0);
    }

    @Test
    void twoCriticalWindowsAreEnoughForCritical() {
        DetectionResult r = run(b().normal(50).latency(2, 2000).build(PAYMENT));
        assertThat(only(r).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(only(r).firstBreachAt()).isEqualTo(T.minusSeconds(20));
    }

    @Test
    void warningLatencyNeedsThreeWindows() {
        assertThat(run(b().normal(50).latency(2, 1000).build(PAYMENT)).anomalies()).isEmpty();
        AnomalyEvent e = only(run(b().normal(50).latency(3, 1000).build(PAYMENT)));
        assertThat(e.severity()).isEqualTo(Severity.WARNING);
        assertThat(e.detectionMethod()).isEqualTo(DetectionMethod.STATIC_THRESHOLD);
        assertThat(e.thresholdValue()).isEqualTo(800.0);
    }

    @Test
    void statisticalDeviationBelowStaticThresholdIsDetectedViaRobustZ() {
        // 400 ms is far below the 800 ms warning threshold but 7.7x the 52 ms baseline
        AnomalyEvent e = only(run(b().normal(50).latency(3, 400).build(PAYMENT)));
        assertThat(e.severity()).isEqualTo(Severity.WARNING);
        assertThat(e.detectionMethod()).isEqualTo(DetectionMethod.ROBUST_Z_SCORE);
        assertThat(e.thresholdValue()).isNull();
        assertThat(e.deviationScore()).isGreaterThan(4.0);
        assertThat(e.description()).contains("deviates from baseline median").contains("robust z");
    }

    @Test
    void ratioOnlyRuleReportsBaselineRatio() {
        var rule = new com.rootradar.apifd.anomaly.DetectionRule("r", com.rootradar.apifd.anomaly.RuleMetric.LATENCY_P95_MS,
                null, true, 800.0, 1500.0, 3.0, null, 3, 3, 2, 60, 12, 3);
        DetectionResult r = run(new com.rootradar.apifd.anomaly.DetectionRuleSet(List.of(rule)), List.of(),
                b().normal(50).latency(3, 400).build(PAYMENT));
        assertThat(only(r).detectionMethod()).isEqualTo(DetectionMethod.BASELINE_RATIO);
        assertThat(only(r).deviationScore()).isBetween(7.0, 8.5);
    }

    @Test
    void smallRelativeIncreaseBelowNoiseFloorIsIgnored() {
        // 160 ms is 3x the baseline median but below 25 % of the warning threshold (200 ms): noise
        assertThat(run(b().normal(50).latency(5, 160).build(PAYMENT)).anomalies()).isEmpty();
    }

    @Test
    void isolatedSpikeNeverCreatesAnAnomaly() {
        for (int spikeLen = 1; spikeLen <= 2; spikeLen++) {
            // spike (even a huge, critical-level one) followed by normal windows
            DetectionResult after = run(b().normal(50).latency(spikeLen, 9000).normal(2).build(PAYMENT));
            assertThat(after.anomalies()).isEmpty();
            assertThat(assessmentOf(after, PAYMENT).state()).isEqualTo(SeriesState.NORMAL);
        }
        // single critical spike as the very latest window
        DetectionResult last = run(b().normal(50).latency(1, 9000).build(PAYMENT));
        assertThat(last.anomalies()).isEmpty();
        assertThat(assessmentOf(last, PAYMENT).consecutiveBreachWindows()).isEqualTo(1);
    }

    @Test
    void alternatingBreachesAreNotConsecutive() {
        MetricSeries s = b().normal(50).latency(1, 3000).normal(1).latency(1, 3000).normal(1).latency(1, 3000).build(PAYMENT);
        assertThat(run(s).anomalies()).isEmpty();
    }

    @Test
    void lowTrafficWindowsAreSkippedNotCountedAsBreachOrNormal() {
        // two real breaching windows, a 1-request window (skipped), then one more breaching window => streak of 3
        Fixtures.Builder bb = b().normal(50).latency(2, 1000);
        bb.errors(1, 1, 0.0); // below minRequests=3 -> skipped
        bb.latency(1, 1000);
        AnomalyEvent e = only(run(bb.build(PAYMENT)));
        assertThat(e.consecutiveBreaches()).isEqualTo(3);
    }

    @Test
    void staleSeriesIsNoData() {
        // series ended 5 minutes before evaluation
        MetricSeries stale = b().normal(30).latency(3, 2000).build(PAYMENT);
        DetectionInput in = new DetectionInput(T.plusSeconds(300), W, List.of(stale), List.of(), DetectionDefaults.ruleSet());
        DetectionResult r = new StatisticalAnomalyDetector().detect(in);
        assertThat(r.anomalies()).isEmpty();
        assertThat(assessmentOf(r, PAYMENT).state()).isEqualTo(SeriesState.NO_DATA);
    }

    @Test
    void emptyAndNullishInputsNeverThrow() {
        DetectionResult r = run(new MetricSeries(PAYMENT, List.of()));
        assertThat(r.anomalies()).isEmpty();
        assertThat(assessmentOf(r, PAYMENT).state()).isEqualTo(SeriesState.NO_DATA);
        DetectionResult none = new StatisticalAnomalyDetector().detect(new DetectionInput(T, W, List.of(), List.of(), null));
        assertThat(none.anomalies()).isEmpty();
        assertThat(none.assessments()).isEmpty();
    }

    @Test
    void windowsEndingAfterEvaluationTimeAreIgnored() {
        MetricSeries s = b().normal(50).latency(3, 2000).build(PAYMENT);
        // evaluate 25 s earlier: the last 3 windows are partly in the future
        DetectionResult r = new StatisticalAnomalyDetector().detect(
                new DetectionInput(T.minusSeconds(30), W, List.of(s), List.of(), DetectionDefaults.ruleSet()));
        assertThat(r.anomalies()).isEmpty();
    }

    // ---------------------------------------------------------------- error rate

    @Test
    void sustainedErrorRateWarning() {
        AnomalyEvent e = only(run(b().normal(50).errors(3, 20, 0.10).build(PAYMENT)));
        assertThat(e.type()).isEqualTo(AnomalyType.ERROR_RATE);
        assertThat(e.severity()).isEqualTo(Severity.WARNING);
        assertThat(e.observedValue()).isEqualTo(0.10);
        assertThat(e.thresholdValue()).isEqualTo(0.05);
        assertThat(e.baselineValue()).isZero();
        assertThat(e.description()).contains("error rate 10.0%").contains("warning threshold 5.0%");
        assertThat(e.fingerprint()).startsWith("ERROR_RATE|");
    }

    @Test
    void highErrorRateForTwoWindowsIsCritical() {
        AnomalyEvent e = only(run(b().normal(50).errors(2, 20, 0.5).build(PAYMENT)));
        assertThat(e.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(e.thresholdValue()).isEqualTo(0.25);
        assertThat(e.consecutiveBreaches()).isEqualTo(2);
    }

    @Test
    void singleFullyFailingWindowIsNotAnAnomaly() {
        assertThat(run(b().normal(50).errors(1, 20, 1.0).build(PAYMENT)).anomalies()).isEmpty();
    }

    @Test
    void errorsInTinyTrafficWindowsAreIgnored() {
        // 4 requests (< minRequests 5), all failing, many windows => skipped entirely
        DetectionResult r = run(b().normal(50).errors(10, 4, 1.0).build(PAYMENT));
        assertThat(r.anomalies()).isEmpty();
    }

    @Test
    void latencyAndErrorAnomaliesAreSeparateEventsForTheSameSeries() {
        Fixtures.Builder bb = b().normal(50);
        for (int i = 0; i < 3; i++) bb.errors(1, 20, 0.5);
        DetectionResult r = run(bb.build(PAYMENT));
        assertThat(r.anomalies()).extracting(AnomalyEvent::type).containsExactly(AnomalyType.ERROR_RATE);
        // now both: build windows that are slow AND failing
        List<AnomalyEvent> both = run(slowAndFailing()).anomalies();
        assertThat(both).extracting(AnomalyEvent::type).containsExactlyInAnyOrder(AnomalyType.LATENCY, AnomalyType.ERROR_RATE);
        assertThat(both).extracting(AnomalyEvent::fingerprint).doesNotHaveDuplicates();
    }

    private static MetricSeries slowAndFailing() {
        List<com.rootradar.apifd.telemetry.MetricPoint> pts = new ArrayList<>();
        for (int i = 0; i < 53; i++) {
            Instant end = T.minusSeconds((long) (52 - i) * W);
            boolean bad = i >= 50;
            pts.add(new com.rootradar.apifd.telemetry.MetricPoint(end.minusSeconds(W), end, 20, bad ? 10 : 0,
                    bad ? 1000 : 30, 40, bad ? 2000 : 52, 60, 80));
        }
        return new MetricSeries(PAYMENT, pts);
    }

    // ---------------------------------------------------------------- insufficient baseline

    @Test
    void insufficientBaselineSkipsStatisticalChecksAndSaysSo() {
        // only 5 baseline windows (< 12): 400 ms would be a statistical anomaly, but must not fire
        DetectionResult r = run(b().normal(5).latency(3, 400).build(PAYMENT));
        assertThat(r.anomalies()).isEmpty();
        SeriesAssessment a = assessmentOf(r, PAYMENT);
        assertThat(a.state()).isEqualTo(SeriesState.INSUFFICIENT_BASELINE);
        assertThat(a.note()).contains("only 5 baseline windows").contains("< 12");
        assertThat(a.baselineLatencyP95Ms()).isNull();
    }

    @Test
    void staticThresholdsStillWorkWithoutBaseline() {
        AnomalyEvent e = only(run(b().normal(2).latency(3, 2000).build(PAYMENT)));
        assertThat(e.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(e.baselineValue()).isNull();
        assertThat(e.baselineSampleCount()).isLessThan(12);
        assertThat(e.description()).contains("baseline insufficient");
    }

    @Test
    void brandNewSeriesWithOnlyFewWindowsIsInsufficientNotNormal() {
        DetectionResult r = run(b().normal(4).build(PAYMENT));
        assertThat(assessmentOf(r, PAYMENT).state()).isEqualTo(SeriesState.INSUFFICIENT_BASELINE);
    }

    // ---------------------------------------------------------------- health checks

    @Test
    void singleFailedHealthCheckIsNotAnAnomaly() {
        DetectionResult r = run(DetectionDefaults.ruleSet(), health("payment-service", true, true, true, false));
        assertThat(r.anomalies()).isEmpty();
        SeriesAssessment a = assessmentOf(r, new SeriesKey("payment-service", MetricScope.HEALTH_CHECK, null, null, null, null));
        assertThat(a.state()).isEqualTo(SeriesState.NORMAL);
        assertThat(a.consecutiveBreachWindows()).isEqualTo(1);
    }

    @Test
    void twoConsecutiveFailuresWarnAndThreeAreCritical() {
        AnomalyEvent w = only(run(DetectionDefaults.ruleSet(), health("payment-service", true, true, false, false)));
        assertThat(w.severity()).isEqualTo(Severity.WARNING);
        assertThat(w.type()).isEqualTo(AnomalyType.HEALTH_CHECK_FAILURE);
        assertThat(w.detectionMethod()).isEqualTo(DetectionMethod.CONSECUTIVE_FAILURES);
        assertThat(w.fingerprint()).isEqualTo(AnomalyEvent.healthFingerprint("payment-service"));
        assertThat(w.firstBreachAt()).isEqualTo(T.minusSeconds(10));

        AnomalyEvent c = only(run(DetectionDefaults.ruleSet(), health("payment-service", true, false, false, false, false)));
        assertThat(c.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(c.consecutiveBreaches()).isEqualTo(4);
        assertThat(c.firstBreachAt()).isEqualTo(T.minusSeconds(30));
        assertThat(c.description()).contains("4 consecutive failed health checks");
    }

    @Test
    void failuresInterruptedBySuccessDoNotAccumulate() {
        assertThat(run(DetectionDefaults.ruleSet(), health("p", false, false, true, false)).anomalies()).isEmpty();
    }

    @Test
    void healthRecoveryCountsConsecutiveSuccesses() {
        DetectionResult r = run(DetectionDefaults.ruleSet(), health("payment-service", false, false, false, true, true));
        assertThat(r.anomalies()).isEmpty();
        SeriesAssessment a = assessmentOf(r, new SeriesKey("payment-service", MetricScope.HEALTH_CHECK, null, null, null, null));
        assertThat(a.consecutiveNormalWindows()).isEqualTo(2);
        assertThat(a.consecutiveBreachWindows()).isZero();
    }

    @Test
    void healthSamplesAreSortedInternallyAndFutureOnesIgnored() {
        List<HealthSample> s = new ArrayList<>(health("p", true, false, false));
        Collections.reverse(s);
        s.add(new HealthSample("p", T.plusSeconds(60), true)); // future
        assertThat(only(run(DetectionDefaults.ruleSet(), s)).consecutiveBreaches()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- recovery

    @Test
    void recoveryAssessmentCountsNormalWindowsAfterAnIncident() {
        // incident of 10 windows at 2000 ms, then 3 normal windows
        MetricSeries three = b().normal(40).latency(10, 2000).normal(3).build(PAYMENT);
        DetectionResult r = run(three);
        assertThat(r.anomalies()).isEmpty();
        SeriesAssessment a = assessmentOf(r, PAYMENT);
        assertThat(a.consecutiveNormalWindows()).isEqualTo(3);
        assertThat(a.consecutiveBreachWindows()).isZero();
        assertThat(a.state()).isEqualTo(SeriesState.NORMAL);

        MetricSeries two = b().normal(40).latency(10, 2000).normal(2).build(PAYMENT);
        assertThat(assessmentOf(run(two), PAYMENT).consecutiveNormalWindows()).isEqualTo(2);
    }

    @Test
    void anomalyStopsBeingReportedOnceWindowsAreNormalAgain() {
        assertThat(run(b().normal(40).latency(5, 2000).build(PAYMENT)).anomalies()).hasSize(1);
        assertThat(run(b().normal(40).latency(5, 2000).normal(1).build(PAYMENT)).anomalies()).isEmpty();
    }

    @Test
    void recoveryDoesNotFlapOnSingleGoodWindowInsideIncident() {
        MetricSeries s = b().normal(40).latency(5, 2000).normal(1).latency(3, 2000).build(PAYMENT);
        AnomalyEvent e = only(run(s));
        assertThat(e.consecutiveBreaches()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- baseline contamination

    @Test
    void longStatisticalIncidentDoesNotPoisonItsOwnBaseline() {
        // 40 windows at 400 ms (below every static threshold) after 30 healthy ones: a naive rolling baseline
        // would absorb them; the baseline must stay around 52 ms and the anomaly must persist.
        DetectionResult r = run(b().normal(30).latency(40, 400).build(PAYMENT));
        AnomalyEvent e = only(r);
        assertThat(e.baselineValue()).isBetween(50.0, 54.0);
        assertThat(e.consecutiveBreaches()).isEqualTo(40);
        assertThat(e.firstBreachAt()).isEqualTo(T.minusSeconds(400));
        assertThat(e.detectionMethod()).isEqualTo(DetectionMethod.ROBUST_Z_SCORE);
    }

    @Test
    void firstBreachAtIsStableWhileIncidentGrows() {
        Instant first = null;
        for (int len = 3; len <= 50; len += 7) {
            AnomalyEvent e = only(run(b().normal(30).latency(len, 400).build(PAYMENT)));
            Instant expected = T.minusSeconds((long) len * W);
            assertThat(e.firstBreachAt()).isEqualTo(expected);
            assertThat(e.baselineValue()).isBetween(50.0, 54.0);
        }
    }

    @Test
    void historicOutageIsQuarantinedFromTheBaseline() {
        // an old outage (25 windows at 2000 ms) sits inside the baseline range; healthy afterwards
        DetectionResult r = run(b().normal(10).latency(25, 2000).normal(30).latency(3, 400).build(PAYMENT));
        AnomalyEvent e = only(r);
        assertThat(e.baselineValue()).isBetween(50.0, 54.0);
        assertThat(((Number) e.details().get("baselineQuarantinedWindows")).intValue()).isGreaterThan(0);
    }

    @Test
    void mostlyHighBaselineStillUsesMedianNotMean() {
        // up to 30 % outliers in the baseline must not move the median
        Fixtures.Builder bb = b();
        for (int i = 0; i < 10; i++) bb.normal(6).latency(1, 700); // below static warning, 14 % outliers
        AnomalyEvent e = only(run(bb.latency(3, 1000).build(PAYMENT)));
        assertThat(e.baselineValue()).isBetween(50.0, 54.0);
    }

    // ---------------------------------------------------------------- scopes / fingerprints / overrides

    @Test
    void dependencyCallSeriesCarriesObserverAndDependencyName() {
        SeriesKey k = SeriesKey.dependency("database", "payment-service", "payment-db");
        AnomalyEvent e = only(run(b().normal(50).latency(3, 2000).build(k)));
        assertThat(e.subjectService()).isEqualTo("database");
        assertThat(e.observedBy()).isEqualTo("payment-service");
        assertThat(e.dependencyName()).isEqualTo("payment-db");
        assertThat(e.scope()).isEqualTo(MetricScope.DEPENDENCY_CALL);
        assertThat(e.fingerprint()).isEqualTo(AnomalyEvent.fingerprintOf(AnomalyType.LATENCY, k));
    }

    @Test
    void endpointSeriesCarriesMethodAndEndpoint() {
        SeriesKey k = SeriesKey.endpoint("gateway-service", "POST", "/api/checkout");
        AnomalyEvent e = only(run(b().normal(50).latency(3, 2000).build(k)));
        assertThat(e.method()).isEqualTo("POST");
        assertThat(e.endpoint()).isEqualTo("/api/checkout");
    }

    @Test
    void serviceSpecificRuleOverridesGlobalAndDisabledRuleSkips() {
        var global = DetectionDefaults.latencyP95();
        var override = new com.rootradar.apifd.anomaly.DetectionRule("payment-lat",
                com.rootradar.apifd.anomaly.RuleMetric.LATENCY_P95_MS, "payment-service", true,
                300.0, 600.0, null, null, 3, 3, 2, 60, 12, 3);
        var rules = new com.rootradar.apifd.anomaly.DetectionRuleSet(List.of(global, override));
        AnomalyEvent e = only(run(rules, List.of(), b().normal(50).latency(2, 700).build(PAYMENT)));
        assertThat(e.ruleKey()).isEqualTo("payment-lat");
        assertThat(e.thresholdValue()).isEqualTo(600.0);

        var disabled = new com.rootradar.apifd.anomaly.DetectionRule("off", override.metric(), "payment-service", false,
                300.0, 600.0, null, null, 3, 3, 2, 60, 12, 3);
        DetectionResult r = run(new com.rootradar.apifd.anomaly.DetectionRuleSet(List.of(disabled)), List.of(),
                b().normal(50).latency(5, 9000).build(PAYMENT));
        assertThat(r.anomalies()).isEmpty();
        assertThat(assessmentOf(r, PAYMENT).state()).isEqualTo(SeriesState.NO_DATA);
    }

    // ---------------------------------------------------------------- determinism

    @Test
    void sameInputGivesIdenticalOutputAndInputOrderIsIrrelevant() {
        SeriesKey db = SeriesKey.dependency("database", "payment-service", "payment-db");
        List<MetricSeries> series = new ArrayList<>(List.of(
                b().normal(50).latency(3, 2000).build(PAYMENT),
                b().normal(50).errors(3, 20, 0.2).build(SeriesKey.inbound("order-service")),
                b().normal(60).build(db)));
        List<HealthSample> health = health("payment-service", true, false, false, false);
        DetectionInput in = new DetectionInput(T, W, series, health, DetectionDefaults.ruleSet());
        StatisticalAnomalyDetector d = new StatisticalAnomalyDetector();
        DetectionResult first = d.detect(in);
        assertThat(d.detect(in)).isEqualTo(first);
        assertThat(new StatisticalAnomalyDetector().detect(in)).isEqualTo(first);

        Random rnd = new Random(42);
        for (int i = 0; i < 5; i++) {
            List<MetricSeries> shuffled = new ArrayList<>();
            for (MetricSeries s : series) {
                List<com.rootradar.apifd.telemetry.MetricPoint> pts = new ArrayList<>(s.points());
                Collections.shuffle(pts, rnd);
                shuffled.add(new MetricSeries(s.key(), pts));
            }
            Collections.shuffle(shuffled, rnd);
            List<HealthSample> h = new ArrayList<>(health);
            Collections.shuffle(h, rnd);
            assertThat(d.detect(new DetectionInput(T, W, shuffled, h, DetectionDefaults.ruleSet()))).isEqualTo(first);
        }
        // does not mutate its input
        assertThat(in.series()).isEqualTo(series);
        assertThat(first.anomalies()).hasSize(3);
    }

    @Test
    void healthyDatabaseNodeStaysNormalWhileCallerIsAnomalous() {
        SeriesKey db = SeriesKey.dependency("database", "payment-service", "payment-db");
        DetectionResult r = run(b().normal(50).latency(3, 2000).build(PAYMENT), b().normal(60).build(db));
        assertThat(only(r).subjectService()).isEqualTo("payment-service");
        SeriesAssessment dbA = assessmentOf(r, db);
        assertThat(dbA.state()).isEqualTo(SeriesState.NORMAL);
        assertThat(dbA.currentLatencyP95Ms()).isBetween(50.0, 54.0);
        assertThat(dbA.firstAbnormalAt()).isNull();
    }
}
