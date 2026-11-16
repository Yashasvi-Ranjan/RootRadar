package com.rootradar.apifd.anomaly;

/**
 * Pure anomaly detection engine (Agent 3). Implementation class:
 * {@code com.rootradar.apifd.anomaly.engine.StatisticalAnomalyDetector}.
 *
 * <p>Contract:
 * <ul>
 *   <li>PURE and deterministic: output depends only on the input; no Spring beans, repositories, clocks, threads,
 *       static mutable state. (The class may carry {@code @Component}; its constructor takes no dependencies.)</li>
 *   <li>Never throws on empty/short/odd data: returns NO_DATA / INSUFFICIENT_BASELINE assessments instead.</li>
 *   <li>No anomaly from a single isolated window: WARNING requires rule.consecutiveWindows consecutive breaching
 *       windows; CRITICAL requires rule.criticalConsecutiveWindows (default 2) windows breaching the critical
 *       threshold. Windows with requestCount &lt; rule.minRequests are skipped (not breach, not normal).</li>
 *   <li>Baseline = median (and MAD for the robust z-score) of valid windows older than the evaluation span, at most
 *       rule.baselineWindows of them; ignored (state INSUFFICIENT_BASELINE) when fewer than rule.minBaselineWindows.
 *       Static thresholds are evaluated regardless of baseline.</li>
 *   <li>Health rule: N consecutive most-recent failed HealthSamples (warning/critical thresholds are counts); a
 *       single failed check never alerts (warning threshold default 2).</li>
 *   <li>Emits events with fingerprints from {@link AnomalyEvent#fingerprintOf} / {@link AnomalyEvent#healthFingerprint}
 *       and firstBreachAt set (see AnomalyEvent Javadoc).</li>
 * </ul>
 */
public interface AnomalyDetector {
    DetectionResult detect(DetectionInput input);
}
