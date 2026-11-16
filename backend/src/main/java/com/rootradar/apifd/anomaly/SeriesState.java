package com.rootradar.apifd.anomaly;

/**
 * Detector verdict for one series after an evaluation.
 * ANOMALOUS: at least one anomaly event emitted for it.
 * NORMAL: enough valid recent windows, baseline sufficient (or no baseline rule applicable), nothing fired.
 * INSUFFICIENT_BASELINE: nothing fired but fewer than minBaselineWindows valid baseline windows existed, so only
 *   static thresholds could be judged; the series is NOT declared normal-with-confidence.
 * NO_DATA: no window with requestCount &gt;= minRequests in the evaluation span.
 */
public enum SeriesState { NORMAL, ANOMALOUS, INSUFFICIENT_BASELINE, NO_DATA }
