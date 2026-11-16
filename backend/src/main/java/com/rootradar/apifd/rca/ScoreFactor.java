package com.rootradar.apifd.rca;

/**
 * One line of the per-rule score breakdown, e.g. ("LATENCY_ANOMALY", "Latency anomaly", +30, 30, true,
 * "p95 2010 ms vs baseline 45 ms (44x)"). Non-fired rules are included with points 0 so the UI can show the full model.
 * Penalties may be negative (then points &lt; 0 and maxPoints 0).
 */
public record ScoreFactor(String ruleKey, String label, int points, int maxPoints, boolean fired, String detail) {}
