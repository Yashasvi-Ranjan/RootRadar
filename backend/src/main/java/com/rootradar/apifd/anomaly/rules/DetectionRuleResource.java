package com.rootradar.apifd.anomaly.rules;

import com.rootradar.apifd.anomaly.RuleMetric;
import java.time.Instant;

/** JSON shape of a detection rule (API_CONTRACTS section 4). */
public record DetectionRuleResource(
        long id, String ruleKey, RuleMetric metric, Long serviceId, String serviceName, boolean enabled,
        Double warningThreshold, Double criticalThreshold, Double baselineRatio, Double zScoreThreshold,
        int minRequests, int consecutiveWindows, int criticalConsecutiveWindows, int baselineWindows,
        int minBaselineWindows, int recoveryWindows, Instant createdAt, Instant updatedAt) {}
