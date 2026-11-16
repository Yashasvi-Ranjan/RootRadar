package com.rootradar.apifd.anomaly.rules;

import com.rootradar.apifd.anomaly.RuleMetric;
import com.rootradar.apifd.common.ApiException;

/** Pure validation of rule values (API_CONTRACTS section 4). Throws 400 VALIDATION_FAILED. */
final class DetectionRuleValidator {
    private DetectionRuleValidator() {}

    static void validate(DetectionRuleEntity r) {
        if (r.getRuleKey() == null || r.getRuleKey().isBlank()) fail("ruleKey is required");
        if (r.getRuleKey().length() > 100) fail("ruleKey must be at most 100 characters");
        if (r.getMetric() == null) fail("metric is required");
        nonNegative("warningThreshold", r.getWarningThreshold());
        nonNegative("criticalThreshold", r.getCriticalThreshold());
        if (r.getWarningThreshold() != null && r.getCriticalThreshold() != null
                && r.getWarningThreshold() > r.getCriticalThreshold()) {
            fail("warningThreshold must be <= criticalThreshold");
        }
        if (r.getMetric() == RuleMetric.ERROR_RATE) {
            fraction("warningThreshold", r.getWarningThreshold());
            fraction("criticalThreshold", r.getCriticalThreshold());
        }
        if (r.getBaselineRatio() != null && !(r.getBaselineRatio() > 1.0)) fail("baselineRatio must be > 1");
        if (r.getZScoreThreshold() != null && !(r.getZScoreThreshold() > 0.0)) fail("zScoreThreshold must be > 0");
        if (r.getMinRequests() < 0) fail("minRequests must be >= 0");
        atLeastOne("consecutiveWindows", r.getConsecutiveWindows());
        atLeastOne("criticalConsecutiveWindows", r.getCriticalConsecutiveWindows());
        atLeastOne("baselineWindows", r.getBaselineWindows());
        atLeastOne("minBaselineWindows", r.getMinBaselineWindows());
        atLeastOne("recoveryWindows", r.getRecoveryWindows());
        if (r.getWarningThreshold() == null && r.getCriticalThreshold() == null
                && r.getBaselineRatio() == null && r.getZScoreThreshold() == null) {
            fail("at least one of warningThreshold, criticalThreshold, baselineRatio, zScoreThreshold is required");
        }
    }

    private static void nonNegative(String n, Double v) {
        if (v != null && (v.isNaN() || v < 0)) fail(n + " must be >= 0");
    }

    private static void fraction(String n, Double v) {
        if (v != null && !(v > 0.0 && v <= 1.0)) fail(n + " must be in (0, 1] for ERROR_RATE");
    }

    private static void atLeastOne(String n, int v) {
        if (v < 1) fail(n + " must be >= 1");
    }

    private static void fail(String msg) {
        throw new ApiException.BadRequest("VALIDATION_FAILED", msg);
    }
}
