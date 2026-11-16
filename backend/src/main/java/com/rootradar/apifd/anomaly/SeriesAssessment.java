package com.rootradar.apifd.anomaly;

import com.rootradar.apifd.telemetry.SeriesKey;
import java.time.Instant;

/**
 * Per-series status after an evaluation (also for series that are fine). Used by the incident engine for recovery
 * hysteresis and by Agent 5 to build RCA {@code ServiceObservation}s (so a NORMAL database is evidence).
 *
 * @param consecutiveBreachWindows trailing windows breaching the lowest applicable (warning) threshold, 0 if none
 * @param consecutiveNormalWindows trailing valid windows that did NOT breach (0 while breaching). Windows below
 *                                 minRequests are skipped, not counted.
 * @param currentLatencyP95Ms      p95 of the latest valid window (null if NO_DATA)
 * @param baselineLatencyP95Ms     median p95 over the baseline windows (null if insufficient)
 * @param currentErrorRate         error rate of the latest valid window (null if NO_DATA)
 * @param baselineErrorRate        median error rate over the baseline windows (null if insufficient)
 * @param recentRequestCount       total requests over the evaluated (non-baseline) windows
 * @param firstAbnormalAt          same as the matching anomaly's firstBreachAt, null if not anomalous
 * @param note                     short explanation, e.g. "only 4 baseline windows (< 12)"
 */
public record SeriesAssessment(
        SeriesKey key,
        SeriesState state,
        Instant lastWindowEnd,
        int consecutiveBreachWindows,
        int consecutiveNormalWindows,
        int baselineSampleCount,
        Double currentLatencyP95Ms,
        Double baselineLatencyP95Ms,
        Double currentErrorRate,
        Double baselineErrorRate,
        long recentRequestCount,
        Instant firstAbnormalAt,
        String note) {}
