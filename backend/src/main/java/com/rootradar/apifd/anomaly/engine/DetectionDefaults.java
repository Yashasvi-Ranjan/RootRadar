package com.rootradar.apifd.anomaly.engine;

import com.rootradar.apifd.anomaly.DetectionRule;
import com.rootradar.apifd.anomaly.DetectionRuleSet;
import com.rootradar.apifd.anomaly.RuleMetric;
import java.util.List;

/**
 * Built-in default rules. They mirror the rows seeded by migration V2 and are used as a fallback when the
 * database holds no rules at all (so detection never silently turns off). Justification: docs/ANOMALY_DETECTION.md.
 */
public final class DetectionDefaults {
    private DetectionDefaults() {}

    public static DetectionRule latencyP95() {
        return new DetectionRule("latency-p95-default", RuleMetric.LATENCY_P95_MS, null, true,
                800.0, 1500.0, 3.0, 4.0, 3, 3, 2, 60, 12, 3);
    }

    public static DetectionRule errorRate() {
        return new DetectionRule("error-rate-default", RuleMetric.ERROR_RATE, null, true,
                0.05, 0.25, null, null, 5, 3, 2, 60, 12, 3);
    }

    /** Thresholds are consecutive failed checks: warning 2, critical 3. */
    public static DetectionRule healthFailures() {
        return new DetectionRule("health-failures-default", RuleMetric.HEALTH_CONSECUTIVE_FAILURES, null, true,
                2.0, 3.0, null, null, 0, 1, 1, 1, 1, 3);
    }

    public static DetectionRuleSet ruleSet() {
        return new DetectionRuleSet(List.of(latencyP95(), errorRate(), healthFailures()));
    }
}
