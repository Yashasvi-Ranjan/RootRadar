package com.rootradar.apifd.anomaly;

import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.telemetry.MetricScope;
import com.rootradar.apifd.telemetry.SeriesKey;
import java.time.Instant;
import java.util.Map;

/**
 * Structured anomaly emitted by the pure detector. No ids: persistence (Agent 5) adds id/incidentId/status
 * (see {@link AnomalyEventView}).
 *
 * <p>The detector emits one event per currently-anomalous series per evaluation (state, not edge): the same ongoing
 * anomaly is re-emitted on every evaluation with the same {@link #fingerprint}; persistence de-duplicates by
 * fingerprint.
 *
 * @param fingerprint          stable identity, see {@link #fingerprintOf}
 * @param subjectService       node that behaves abnormally (for DEPENDENCY_CALL: the downstream node)
 * @param observedBy           caller that measured it (DEPENDENCY_CALL only), else null
 * @param method               HTTP method (ENDPOINT only), else null
 * @param endpoint             endpoint template (ENDPOINT only), else null
 * @param dependencyName       caller-side dependency name (DEPENDENCY_CALL only), else null
 * @param detectedAt           evaluation time (DetectionInput.evaluatedAt)
 * @param firstBreachAt        windowStart of the EARLIEST window of the current consecutive breach streak
 *                             ("when did it first look abnormal"; key RCA input)
 * @param windowStart          start of the first window examined in this evaluation's streak (== firstBreachAt unless clipped)
 * @param windowEnd            end of the latest window examined
 * @param observedValue        latest value in metric units (e.g. p95 ms, error rate fraction, failed checks)
 * @param baselineValue        baseline median in the same unit, null if insufficient baseline / not applicable
 * @param thresholdValue       the threshold that was crossed (the one matching severity), null for pure statistical
 * @param deviationScore       observed/baseline ratio for BASELINE_RATIO, robust z for ROBUST_Z_SCORE, else null
 * @param consecutiveBreaches  length of the current breach streak in windows (or failed checks)
 * @param sampleCount          windows (or checks) with enough traffic that were examined
 * @param baselineSampleCount  valid windows that formed the baseline
 * @param description          one human sentence, e.g. "p95 latency 2012 ms >= critical threshold 1500 ms for 3 consecutive windows"
 * @param details              extra machine-readable evidence (rule values used, MAD, baseline stats ...), JSON-serialisable
 */
public record AnomalyEvent(
        String fingerprint,
        AnomalyType type,
        Severity severity,
        String subjectService,
        MetricScope scope,
        String observedBy,
        String method,
        String endpoint,
        String dependencyName,
        DetectionMethod detectionMethod,
        String ruleKey,
        Instant detectedAt,
        Instant firstBreachAt,
        Instant windowStart,
        Instant windowEnd,
        double observedValue,
        Double baselineValue,
        Double thresholdValue,
        Double deviationScore,
        int consecutiveBreaches,
        int sampleCount,
        int baselineSampleCount,
        String description,
        Map<String, Object> details) {

    /** Canonical fingerprint: {@code TYPE|SCOPE|subject|method endpoint|dependency|observedBy}. Detector MUST use this. */
    public static String fingerprintOf(AnomalyType type, SeriesKey key) {
        return type + "|" + key.asFingerprintPart();
    }

    /** Fingerprint for health-check anomalies. */
    public static String healthFingerprint(String service) {
        return AnomalyType.HEALTH_CHECK_FAILURE + "|" + MetricScope.HEALTH_CHECK + "|" + service + "|||";
    }
}
