package com.rootradar.apifd.anomaly.engine;

import com.rootradar.apifd.anomaly.DetectionInput;
import com.rootradar.apifd.anomaly.DetectionResult;
import com.rootradar.apifd.anomaly.DetectionRuleSet;
import com.rootradar.apifd.anomaly.SeriesAssessment;
import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.monitoring.HealthSample;
import com.rootradar.apifd.telemetry.MetricPoint;
import com.rootradar.apifd.telemetry.MetricSeries;
import com.rootradar.apifd.telemetry.SeriesKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Test data builders. Windows are 10 s long and the last window always ends at {@link #T}. */
final class Fixtures {
    static final Instant T = Instant.parse("2026-01-01T12:00:00Z");
    static final int W = 10;
    static final SeriesKey PAYMENT = SeriesKey.inbound("payment-service");

    private Fixtures() {}

    /** Mutable list of windows, oldest first; {@link #build} places them so the last one ends at T. */
    static final class Builder {
        private final List<double[]> w = new ArrayList<>(); // {requests, errors, p95, avg}

        /** n healthy windows: 20 requests, p95 = 50 + (i % 5) ms (median 52, tiny MAD), 0 errors. */
        Builder normal(int n) {
            for (int i = 0; i < n; i++) w.add(new double[] {20, 0, 50 + (i % 5), 30});
            return this;
        }

        Builder latency(int n, double p95) {
            for (int i = 0; i < n; i++) w.add(new double[] {20, 0, p95, p95 / 2});
            return this;
        }

        /** n windows with the given traffic and error fraction, normal latency. */
        Builder errors(int n, int requests, double errorFraction) {
            for (int i = 0; i < n; i++) w.add(new double[] {requests, Math.round(requests * errorFraction), 52, 30});
            return this;
        }

        MetricSeries build(SeriesKey key) {
            List<MetricPoint> pts = new ArrayList<>();
            int n = w.size();
            for (int i = 0; i < n; i++) {
                Instant end = T.minusSeconds((long) (n - 1 - i) * W);
                double[] x = w.get(i);
                pts.add(new MetricPoint(end.minusSeconds(W), end, (long) x[0], (long) x[1], x[3], x[2] * 0.8, x[2], x[2] * 1.1, x[2] * 1.5));
            }
            return new MetricSeries(key, pts);
        }
    }

    static Builder b() { return new Builder(); }

    static DetectionResult run(MetricSeries... series) {
        return run(DetectionDefaults.ruleSet(), List.of(), series);
    }

    static DetectionResult run(DetectionRuleSet rules, List<HealthSample> health, MetricSeries... series) {
        return new StatisticalAnomalyDetector().detect(new DetectionInput(T, W, List.of(series), health, rules));
    }

    static AnomalyEvent only(DetectionResult r) {
        if (r.anomalies().size() != 1) throw new AssertionError("expected 1 anomaly, got " + r.anomalies());
        return r.anomalies().get(0);
    }

    static SeriesAssessment assessmentOf(DetectionResult r, SeriesKey k) {
        return r.assessments().stream().filter(a -> a.key().equals(k)).findFirst().orElseThrow();
    }

    /** Health samples every 10 s ending at T; pattern oldest-first, true = up. */
    static List<HealthSample> health(String svc, boolean... upOldestFirst) {
        List<HealthSample> l = new ArrayList<>();
        int n = upOldestFirst.length;
        for (int i = 0; i < n; i++) l.add(new HealthSample(svc, T.minusSeconds((long) (n - 1 - i) * 10), upOldestFirst[i]));
        return l;
    }
}
