package com.rootradar.apifd.rca.engine;

import java.time.Duration;

/**
 * Every weight, penalty and threshold of the RCA scoring model in one place. The numbers here MUST equal the tables in
 * docs/RCA_SCORING_MODEL.md. Positive weights sum to exactly 100.
 */
public final class ScoringModel {
    private ScoringModel() {}

    public static final String MODEL_VERSION = "rca-rules-1.0";

    // positive rule weights (sum = 100)
    public static final int LATENCY_MAX = 20;
    public static final int ERROR_MAX = 10;
    public static final int ONSET_MAX = 20;
    public static final int DOWNSTREAM_MAX = 20;
    public static final int UPSTREAM_MAX = 10;
    public static final int MAGNITUDE_MAX = 10;
    public static final int CORROBORATION_MAX = 5;
    public static final int QUALITY_MAX = 5;

    /** Points when the strongest anomaly of that kind is only WARNING (CRITICAL earns the full weight). */
    public static final int LATENCY_WARNING = 12;
    public static final int ERROR_WARNING = 6;

    // penalties
    public static final int PENALTY_DOWNSTREAM_EXPLAINS = -25;
    public static final int PENALTY_DOWNSTREAM_LATER = -10;
    public static final int PENALTY_NO_TELEMETRY = -10;

    // onset reasoning
    public static final Duration ONSET_TOLERANCE = Duration.ofSeconds(30);
    /** Onset later than earliest-related + tolerance*3 earns 0; between tolerance and 3*tolerance earns ONSET_PARTIAL. */
    public static final int ONSET_PARTIAL_MULTIPLIER = 3;
    public static final int ONSET_PARTIAL = 8;

    // classification
    public static final int PROBABLE_CAUSE_MIN = 60;
    public static final int CONTRIBUTING_MIN = 30;
    /** Two candidates whose scores differ by at most this many points cannot be separated. */
    public static final int TIE_MARGIN = 5;

    /** Magnitude ladder for latency ratio (current p95 / baseline p95), points for ratio >= threshold. */
    public static final double[] RATIO_STEPS = {10, 5, 3, 2, 1.5};
    public static final int[] RATIO_POINTS = {10, 8, 6, 4, 2};
    /** Magnitude ladder for absolute error-rate increase (fraction), points for delta >= threshold. */
    public static final double[] ERROR_DELTA_STEPS = {0.50, 0.25, 0.10, 0.05, 0.02};
    public static final int[] ERROR_DELTA_POINTS = {10, 8, 6, 4, 2};
}
