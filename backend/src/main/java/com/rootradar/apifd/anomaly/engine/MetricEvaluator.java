package com.rootradar.apifd.anomaly.engine;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.anomaly.AnomalyType;
import com.rootradar.apifd.anomaly.DetectionMethod;
import com.rootradar.apifd.anomaly.DetectionRule;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.telemetry.MetricPoint;
import com.rootradar.apifd.telemetry.SeriesKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates ONE metric (latency p95, latency avg or error rate) of ONE series against ONE rule. Pure and stateless.
 * See docs/ANOMALY_DETECTION.md for the algorithm.
 */
final class MetricEvaluator {

    enum Kind {
        LATENCY_P95("p95 latency", "ms", AnomalyType.LATENCY, 0.10, 1.0) {
            double value(MetricPoint p) { return p.p95Ms(); }
        },
        LATENCY_AVG("average latency", "ms", AnomalyType.LATENCY, 0.10, 1.0) {
            double value(MetricPoint p) { return p.avgMs(); }
        },
        ERROR_RATE("error rate", "%", AnomalyType.ERROR_RATE, 0.0, 0.01) {
            double value(MetricPoint p) { return p.errorRate(); }
        };

        final String label;
        final String unit;
        final AnomalyType type;
        /** Relative floor of the robust scale (fraction of the median). */
        final double relFloor;
        /** Absolute floor of the robust scale and of the baseline used as ratio denominator (metric units). */
        final double absFloor;

        Kind(String label, String unit, AnomalyType type, double relFloor, double absFloor) {
            this.label = label; this.unit = unit; this.type = type; this.relFloor = relFloor; this.absFloor = absFloor;
        }

        abstract double value(MetricPoint p);

        String fmt(double v) {
            return this == ERROR_RATE ? String.format(Locale.ROOT, "%.1f%%", v * 100.0)
                    : String.format(Locale.ROOT, "%.0f ms", v);
        }
    }

    enum Status { NO_DATA, NORMAL, INSUFFICIENT_BASELINE, ANOMALOUS }

    /** Fraction of the warning threshold below which relative/z-score deviations are treated as noise. */
    static final double NOISE_FLOOR_FRACTION = 0.25;
    /** Latest valid window older than (span + this many) windows makes the series stale (NO_DATA). */
    static final int STALE_EXTRA_WINDOWS = 3;

    record Result(Kind kind, DetectionRule rule, Status status, int warnStreak, int critStreak, int normalStreak,
                  MetricPoint latest, Double latestValue, Double baselineMedian, int baselineCount,
                  long recentRequests, Instant firstAbnormalAt, AnomalyEvent event, String note) {}

    private record Verdict(int level, boolean staticWarn, boolean staticCrit, boolean statistical,
                           Double ratio, Double z) {}

    private record Baseline(RollingWindowStats stats, int excluded) {
        int count() { return stats.count(); }
    }

    private final Kind kind;
    private final DetectionRule rule;

    MetricEvaluator(Kind kind, DetectionRule rule) {
        this.kind = kind;
        this.rule = rule;
    }

    Result evaluate(SeriesKey key, List<MetricPoint> points, Instant evaluatedAt, int windowSeconds) {
        List<MetricPoint> v = new ArrayList<>();
        for (MetricPoint p : points) if (p.requestCount() >= Math.max(1, rule.minRequests())) v.add(p);
        int n = v.size();
        int spanN = Math.max(Math.max(rule.consecutiveWindows(), rule.criticalConsecutiveWindows()), rule.recoveryWindows());
        if (n == 0) return noData("no window with >= " + rule.minRequests() + " requests");
        MetricPoint latest = v.get(n - 1);
        Instant staleBefore = evaluatedAt.minus(Duration.ofSeconds((long) (spanN + STALE_EXTRA_WINDOWS) * windowSeconds));
        if (latest.windowEnd().isBefore(staleBefore)) {
            return noData("latest valid window ended " + latest.windowEnd() + ", stale");
        }

        int spanStart = Math.max(0, n - spanN);
        Baseline base = baseline(v, spanStart);
        Streaks s = streaks(v, base);
        boolean baselineOk = base.count() >= rule.minBaselineWindows();
        boolean statisticalConfigured = rule.baselineRatio() != null || rule.zScoreThreshold() != null;
        Double baselineMedian = baselineOk ? base.stats().median() : null;

        long recent = 0;
        for (int i = spanStart; i < n; i++) recent += v.get(i).requestCount();
        double latestValue = kind.value(latest);

        AnomalyEvent event = null;
        Instant firstAbnormal = null;
        Severity sev = null;
        if (s.crit >= rule.criticalConsecutiveWindows() && rule.criticalThreshold() != null) sev = Severity.CRITICAL;
        else if (s.warn >= rule.consecutiveWindows()) sev = Severity.WARNING;
        if (sev != null) {
            firstAbnormal = v.get(n - s.warn).windowStart();
            event = buildEvent(key, sev, v, n, s, base, baselineOk, latest, latestValue, evaluatedAt, firstAbnormal, spanN);
        }

        Status status;
        String note;
        if (event != null) {
            status = Status.ANOMALOUS;
            note = kind.label + " anomalous";
        } else if (statisticalConfigured && !baselineOk) {
            status = Status.INSUFFICIENT_BASELINE;
            note = "only " + base.count() + " baseline windows (< " + rule.minBaselineWindows() + ")";
        } else {
            status = Status.NORMAL;
            note = null;
        }
        return new Result(kind, rule, status, s.warn, s.crit, s.normal, latest, latestValue, baselineMedian,
                base.count(), recent, firstAbnormal, event, note);
    }

    private Result noData(String note) {
        return new Result(kind, rule, Status.NO_DATA, 0, 0, 0, null, null, null, 0, 0, null, null, note);
    }

    /**
     * Quarantining baseline. Valid windows are replayed oldest to newest; each window is judged against the baseline
     * accumulated BEFORE it. Windows that breach (static warning threshold, or statistical deviation once the
     * baseline is sufficient) are quarantined and never enter the baseline, so neither an isolated spike nor a long
     * ongoing anomaly (even one below every static threshold) can drift the baseline towards itself.
     * The newest `spanN` windows (the evaluation span) are never part of the baseline. The baseline is the last
     * baselineWindows accepted windows older than the span.
     */
    private Baseline baseline(List<MetricPoint> v, int endExclusive) {
        java.util.ArrayDeque<Double> accepted = new java.util.ArrayDeque<>();
        int excluded = 0;
        for (int i = 0; i < endExclusive; i++) {
            double x = kind.value(v.get(i));
            boolean breach;
            if (rule.warningThreshold() != null && x >= rule.warningThreshold()) {
                breach = true;
            } else if (accepted.size() >= rule.minBaselineWindows()) {
                breach = verdict(x, new Baseline(RollingWindowStats.of(new ArrayList<>(accepted)), 0)).level >= 1;
            } else {
                breach = false;
            }
            if (breach) { excluded++; continue; }
            accepted.addLast(x);
            if (accepted.size() > rule.baselineWindows()) accepted.removeFirst();
        }
        return new Baseline(RollingWindowStats.of(new ArrayList<>(accepted)), excluded);
    }

    private record Streaks(int warn, int crit, int normal) {}

    private Streaks streaks(List<MetricPoint> v, Baseline base) {
        int n = v.size();
        int warn = 0, crit = 0, normal = 0;
        for (int i = n - 1; i >= 0 && verdict(kind.value(v.get(i)), base).level >= 1; i--) warn++;
        for (int i = n - 1; i >= 0 && verdict(kind.value(v.get(i)), base).level == 2; i--) crit++;
        for (int i = n - 1; i >= 0 && verdict(kind.value(v.get(i)), base).level == 0; i--) normal++;
        return new Streaks(warn, crit, normal);
    }

    private Verdict verdict(double x, Baseline base) {
        boolean sw = rule.warningThreshold() != null && x >= rule.warningThreshold();
        boolean sc = rule.criticalThreshold() != null && x >= rule.criticalThreshold();
        Double ratio = null, z = null;
        boolean stat = false;
        boolean ok = base.count() >= rule.minBaselineWindows();
        if (ok && (rule.baselineRatio() != null || rule.zScoreThreshold() != null)) {
            RollingWindowStats st = base.stats();
            ratio = x / Math.max(st.median(), kind.absFloor);
            z = st.robustZ(x, kind.relFloor, kind.absFloor);
            boolean aboveNoise = x >= noiseFloor();
            boolean ratioOk = rule.baselineRatio() == null || ratio >= rule.baselineRatio();
            boolean zOk = rule.zScoreThreshold() == null || z >= rule.zScoreThreshold();
            stat = aboveNoise && ratioOk && zOk;
        }
        int level = sc ? 2 : (sw || stat ? 1 : 0);
        return new Verdict(level, sw, sc, stat, ratio, z);
    }

    private double noiseFloor() {
        if (rule.warningThreshold() != null) return NOISE_FLOOR_FRACTION * rule.warningThreshold();
        if (rule.criticalThreshold() != null) return 0.1 * rule.criticalThreshold();
        return 0.0;
    }

    private AnomalyEvent buildEvent(SeriesKey key, Severity sev, List<MetricPoint> v, int n, Streaks s, Baseline base,
                                    boolean baselineOk, MetricPoint latest, double latestValue, Instant evaluatedAt,
                                    Instant firstAbnormal, int spanN) {
        Verdict lv = verdict(latestValue, base);
        DetectionMethod method;
        Double threshold;
        Double deviation;
        String how;
        int streak = sev == Severity.CRITICAL ? s.crit : s.warn;
        if (sev == Severity.CRITICAL) {
            method = DetectionMethod.STATIC_THRESHOLD;
            threshold = rule.criticalThreshold();
            deviation = baselineOk ? ratioOf(latestValue, base) : null;
            how = kind.label + " " + kind.fmt(latestValue) + " >= critical threshold " + kind.fmt(threshold);
        } else if (lv.staticWarn) {
            method = DetectionMethod.STATIC_THRESHOLD;
            threshold = rule.warningThreshold();
            deviation = baselineOk ? ratioOf(latestValue, base) : null;
            how = kind.label + " " + kind.fmt(latestValue) + " >= warning threshold " + kind.fmt(threshold);
        } else {
            threshold = null;
            if (rule.zScoreThreshold() != null) {
                method = DetectionMethod.ROBUST_Z_SCORE;
                deviation = lv.z;
            } else {
                method = DetectionMethod.BASELINE_RATIO;
                deviation = lv.ratio;
            }
            how = kind.label + " " + kind.fmt(latestValue) + " deviates from baseline median "
                    + kind.fmt(base.stats().median()) + " (" + String.format(Locale.ROOT, "%.1fx", lv.ratio)
                    + (lv.z != null ? ", robust z " + String.format(Locale.ROOT, "%.1f", lv.z) : "") + ")";
        }
        String desc = how + " for " + streak + " consecutive window" + (streak == 1 ? "" : "s");
        if (baselineOk && method == DetectionMethod.STATIC_THRESHOLD) {
            desc += "; baseline median " + kind.fmt(base.stats().median()) + " ("
                    + String.format(Locale.ROOT, "%.1fx", ratioOf(latestValue, base)) + ")";
        } else if (!baselineOk) {
            desc += "; baseline insufficient (" + base.count() + " < " + rule.minBaselineWindows() + " windows)";
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("metric", kind.name());
        d.put("ruleKey", rule.ruleKey());
        d.put("unit", kind.unit);
        d.put("warningThreshold", rule.warningThreshold());
        d.put("criticalThreshold", rule.criticalThreshold());
        d.put("baselineRatioLimit", rule.baselineRatio());
        d.put("zScoreLimit", rule.zScoreThreshold());
        d.put("warningStreak", s.warn);
        d.put("criticalStreak", s.crit);
        d.put("requiredWarningWindows", rule.consecutiveWindows());
        d.put("requiredCriticalWindows", rule.criticalConsecutiveWindows());
        d.put("baselineWindowsUsed", base.count());
        d.put("baselineQuarantinedWindows", base.excluded());
        if (baselineOk) {
            d.put("baselineMedian", round(base.stats().median()));
            d.put("baselineMad", round(base.stats().mad()));
            d.put("baselineMean", round(base.stats().mean()));
            d.put("baselineStdDev", round(base.stats().stdDev()));
            if (lv.ratio != null) d.put("ratioToBaseline", round(lv.ratio));
            if (lv.z != null) d.put("robustZ", round(lv.z));
        }
        d.put("latestRequestCount", latest.requestCount());
        return new AnomalyEvent(
                AnomalyEvent.fingerprintOf(kind.type, key), kind.type, sev, key.serviceName(), key.scope(),
                key.observedBy(), key.method(), key.endpoint(), key.dependencyName(), method, rule.ruleKey(),
                evaluatedAt, firstAbnormal, firstAbnormal, latest.windowEnd(), latestValue,
                baselineOk ? base.stats().median() : null, threshold, deviation, streak,
                Math.max(streak, Math.min(n, spanN)), base.count(), desc, d);
    }

    private Double ratioOf(double x, Baseline base) {
        return x / Math.max(base.stats().median(), kind.absFloor);
    }

    private static double round(double x) {
        return Math.round(x * 1000.0) / 1000.0;
    }
}
