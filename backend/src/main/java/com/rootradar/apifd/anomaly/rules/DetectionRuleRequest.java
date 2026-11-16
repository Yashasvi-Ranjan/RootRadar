package com.rootradar.apifd.anomaly.rules;

import com.rootradar.apifd.anomaly.RuleMetric;

/**
 * Create/update body. On create, null integer fields take the defaults below; on PUT, ruleKey/metric/serviceId are
 * ignored (immutable) and every other field replaces the stored value (null integers keep their stored value).
 */
public record DetectionRuleRequest(
        String ruleKey, RuleMetric metric, Long serviceId, Boolean enabled,
        Double warningThreshold, Double criticalThreshold, Double baselineRatio, Double zScoreThreshold,
        Integer minRequests, Integer consecutiveWindows, Integer criticalConsecutiveWindows, Integer baselineWindows,
        Integer minBaselineWindows, Integer recoveryWindows) {}
