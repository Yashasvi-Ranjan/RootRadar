package com.rootradar.apifd.anomaly.engine;

import com.rootradar.apifd.anomaly.AnomalyDetector;
import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.anomaly.AnomalyType;
import com.rootradar.apifd.anomaly.DetectionInput;
import com.rootradar.apifd.anomaly.DetectionMethod;
import com.rootradar.apifd.anomaly.DetectionResult;
import com.rootradar.apifd.anomaly.DetectionRule;
import com.rootradar.apifd.anomaly.DetectionRuleSet;
import com.rootradar.apifd.anomaly.RuleMetric;
import com.rootradar.apifd.anomaly.SeriesAssessment;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.monitoring.HealthSample;
import com.rootradar.apifd.telemetry.MetricPoint;
import com.rootradar.apifd.telemetry.MetricScope;
import com.rootradar.apifd.telemetry.MetricSeries;
import com.rootradar.apifd.telemetry.SeriesKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Pure, deterministic detector (no collaborators, no clock, no mutable state). Combines static thresholds,
 * baseline ratio and a robust (median/MAD) z-score for metric series, plus the consecutive-failure rule for health
 * checks. Algorithm and defaults: docs/ANOMALY_DETECTION.md.
 */
@Component
public class StatisticalAnomalyDetector implements AnomalyDetector {

    @Override
    public DetectionResult detect(DetectionInput input) {
        Instant now = input.evaluatedAt();
        DetectionRuleSet rules = input.rules() == null ? new DetectionRuleSet(List.of()) : input.rules();
        List<AnomalyEvent> anomalies = new ArrayList<>();
        // sorted map => deterministic output order independent of input order
        Map<String, SeriesAssessment> assessments = new TreeMap<>();

        List<MetricSeries> series = input.series() == null ? List.of() : input.series();
        for (MetricSeries s : series) {
            if (s == null || s.key() == null) continue;
            evaluateSeries(s, input, rules, now, anomalies, assessments);
        }
        evaluateHealth(input, rules, now, anomalies, assessments);

        anomalies.sort(Comparator.comparing(AnomalyEvent::fingerprint).thenComparing(e -> e.severity().name()));
        return new DetectionResult(now, List.copyOf(anomalies), List.copyOf(assessments.values()));
    }

    // ------------------------------------------------------------------ metric series

    private void evaluateSeries(MetricSeries s, DetectionInput in, DetectionRuleSet rules, Instant now,
                                List<AnomalyEvent> anomalies, Map<String, SeriesAssessment> assessments) {
        SeriesKey key = s.key();
        List<MetricPoint> pts = new ArrayList<>();
        if (s.points() != null) {
            for (MetricPoint p : s.points()) if (p != null && !p.windowEnd().isAfter(now)) pts.add(p);
        }
        pts.sort(Comparator.comparing(MetricPoint::windowStart));

        List<MetricEvaluator.Result> results = new ArrayList<>();
        resolve(rules, key, RuleMetric.LATENCY_P95_MS).ifPresent(r -> results.add(
                new MetricEvaluator(MetricEvaluator.Kind.LATENCY_P95, r).evaluate(key, pts, now, in.windowSeconds())));
        resolve(rules, key, RuleMetric.LATENCY_AVG_MS).ifPresent(r -> results.add(
                new MetricEvaluator(MetricEvaluator.Kind.LATENCY_AVG, r).evaluate(key, pts, now, in.windowSeconds())));
        resolve(rules, key, RuleMetric.ERROR_RATE).ifPresent(r -> results.add(
                new MetricEvaluator(MetricEvaluator.Kind.ERROR_RATE, r).evaluate(key, pts, now, in.windowSeconds())));

        // one event per fingerprint: keep the most severe (ties: first evaluated, i.e. p95 before avg)
        Map<String, AnomalyEvent> byFp = new LinkedHashMap<>();
        for (MetricEvaluator.Result r : results) {
            if (r.event() == null) continue;
            AnomalyEvent prev = byFp.get(r.event().fingerprint());
            if (prev == null || r.event().severity().isAtLeast(Severity.CRITICAL) && !prev.severity().isAtLeast(Severity.CRITICAL)) {
                byFp.put(r.event().fingerprint(), r.event());
            }
        }
        anomalies.addAll(byFp.values());
        assessments.put(key.asFingerprintPart(), assess(key, results, byFp.isEmpty() ? null : byFp.values().iterator().next(), pts));
    }

    private static Optional<DetectionRule> resolve(DetectionRuleSet rules, SeriesKey key, RuleMetric m) {
        return rules.resolve(key.serviceName(), m);
    }

    private SeriesAssessment assess(SeriesKey key, List<MetricEvaluator.Result> results, AnomalyEvent any,
                                    List<MetricPoint> pts) {
        if (results.isEmpty()) {
            return new SeriesAssessment(key, SeriesState.NO_DATA, lastEnd(pts), 0, 0, 0, null, null, null, null, 0, null,
                    "no enabled rule applies to this series");
        }
        boolean anomalous = results.stream().anyMatch(r -> r.status() == MetricEvaluator.Status.ANOMALOUS);
        boolean allNoData = results.stream().allMatch(r -> r.status() == MetricEvaluator.Status.NO_DATA);
        boolean insufficient = results.stream().anyMatch(r -> r.status() == MetricEvaluator.Status.INSUFFICIENT_BASELINE);
        SeriesState state = anomalous ? SeriesState.ANOMALOUS : allNoData ? SeriesState.NO_DATA
                : insufficient ? SeriesState.INSUFFICIENT_BASELINE : SeriesState.NORMAL;

        int breach = 0;
        int normal = Integer.MAX_VALUE;
        int baselineCount = 0;
        long recent = 0;
        Double curLat = null, baseLat = null, curErr = null, baseErr = null;
        Instant firstAbnormal = null;
        List<String> notes = new ArrayList<>();
        for (MetricEvaluator.Result r : results) {
            if (r.status() == MetricEvaluator.Status.NO_DATA) {
                if (r.note() != null) notes.add(r.kind() + ": " + r.note());
                continue;
            }
            breach = Math.max(breach, r.warnStreak());
            normal = Math.min(normal, r.normalStreak());
            baselineCount = Math.max(baselineCount, r.baselineCount());
            recent = Math.max(recent, r.recentRequests());
            if (r.kind() == MetricEvaluator.Kind.LATENCY_P95) {
                curLat = r.latestValue();
                baseLat = r.baselineMedian();
            } else if (r.kind() == MetricEvaluator.Kind.ERROR_RATE) {
                curErr = r.latestValue();
                baseErr = r.baselineMedian();
            }
            if (r.firstAbnormalAt() != null && (firstAbnormal == null || r.firstAbnormalAt().isBefore(firstAbnormal))) {
                firstAbnormal = r.firstAbnormalAt();
            }
            if (r.note() != null) notes.add(r.kind() + ": " + r.note());
        }
        if (normal == Integer.MAX_VALUE) normal = 0;
        if (breach > 0) normal = 0;
        // latest values for metrics that have no rule are still useful evidence (e.g. latency of a DB node)
        MetricPoint lastPoint = pts.isEmpty() ? null : pts.get(pts.size() - 1);
        if (curLat == null && lastPoint != null && lastPoint.hasTraffic() && state != SeriesState.NO_DATA) curLat = lastPoint.p95Ms();
        if (curErr == null && lastPoint != null && lastPoint.hasTraffic() && state != SeriesState.NO_DATA) curErr = lastPoint.errorRate();
        return new SeriesAssessment(key, state, lastEnd(pts), breach, normal, baselineCount, curLat, baseLat, curErr,
                baseErr, recent, firstAbnormal, notes.isEmpty() ? null : String.join("; ", notes));
    }

    private static Instant lastEnd(List<MetricPoint> pts) {
        return pts.isEmpty() ? null : pts.get(pts.size() - 1).windowEnd();
    }

    // ------------------------------------------------------------------ health checks

    private void evaluateHealth(DetectionInput in, DetectionRuleSet rules, Instant now, List<AnomalyEvent> anomalies,
                                Map<String, SeriesAssessment> assessments) {
        if (in.healthSamples() == null || in.healthSamples().isEmpty()) return;
        Map<String, List<HealthSample>> byService = new TreeMap<>();
        for (HealthSample h : in.healthSamples()) {
            if (h == null || h.checkedAt() == null || h.checkedAt().isAfter(now)) continue;
            byService.computeIfAbsent(h.serviceName(), k -> new ArrayList<>()).add(h);
        }
        for (Map.Entry<String, List<HealthSample>> e : byService.entrySet()) {
            String service = e.getKey();
            List<HealthSample> list = e.getValue();
            list.sort(Comparator.comparing(HealthSample::checkedAt));
            SeriesKey key = new SeriesKey(service, MetricScope.HEALTH_CHECK, null, null, null, null);
            Optional<DetectionRule> rule = rules.resolve(service, RuleMetric.HEALTH_CONSECUTIVE_FAILURES);
            Instant last = list.get(list.size() - 1).checkedAt();
            if (rule.isEmpty()) {
                assessments.put(key.asFingerprintPart(), new SeriesAssessment(key, SeriesState.NO_DATA, last, 0, 0, 0,
                        null, null, null, null, 0, null, "no enabled health rule"));
                continue;
            }
            DetectionRule r = rule.get();
            int fails = 0;
            for (int i = list.size() - 1; i >= 0 && !list.get(i).up(); i--) fails++;
            int ups = 0;
            if (fails == 0) for (int i = list.size() - 1; i >= 0 && list.get(i).up(); i--) ups++;

            int warnAt = r.warningThreshold() == null ? Integer.MAX_VALUE : (int) Math.ceil(r.warningThreshold());
            int critAt = r.criticalThreshold() == null ? Integer.MAX_VALUE : (int) Math.ceil(r.criticalThreshold());
            Severity sev = fails >= critAt ? Severity.CRITICAL : fails >= warnAt ? Severity.WARNING : null;
            Instant firstFail = fails > 0 ? list.get(list.size() - fails).checkedAt() : null;
            if (sev != null) {
                double thr = sev == Severity.CRITICAL ? critAt : warnAt;
                String desc = fails + " consecutive failed health checks (>= " + (int) thr + " "
                        + sev.name().toLowerCase(java.util.Locale.ROOT) + " threshold); first failure at " + firstFail
                        + ", latest check at " + last;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("ruleKey", r.ruleKey());
                d.put("warningThreshold", r.warningThreshold());
                d.put("criticalThreshold", r.criticalThreshold());
                d.put("consecutiveFailures", fails);
                d.put("checksExamined", list.size());
                d.put("lastCheckAt", last.toString());
                anomalies.add(new AnomalyEvent(AnomalyEvent.healthFingerprint(service), AnomalyType.HEALTH_CHECK_FAILURE,
                        sev, service, MetricScope.HEALTH_CHECK, null, null, null, null,
                        DetectionMethod.CONSECUTIVE_FAILURES, r.ruleKey(), now, firstFail, firstFail, last, fails,
                        null, thr, null, fails, list.size(), 0, desc, d));
            }
            SeriesState state = sev != null ? SeriesState.ANOMALOUS : SeriesState.NORMAL;
            assessments.put(key.asFingerprintPart(), new SeriesAssessment(key, state, last, fails, ups, 0, null, null,
                    null, null, 0, sev != null ? firstFail : null,
                    sev != null ? null : fails > 0 ? fails + " failed check(s), below threshold " + warnAt : null));
        }
    }
}
