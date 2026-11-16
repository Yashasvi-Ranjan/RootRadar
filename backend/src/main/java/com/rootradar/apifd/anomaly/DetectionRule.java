package com.rootradar.apifd.anomaly;

/**
 * One configurable rule (row of detection_rules). {@code serviceName == null} = global default; a service-specific
 * enabled rule for the same metric overrides the global one for that service.
 *
 * @param warningThreshold          value at/above which a window counts as a WARNING breach (nullable = rule not used)
 * @param criticalThreshold         value at/above which a window counts as a CRITICAL breach (nullable)
 * @param baselineRatio             observed / baseline-median ratio considered a deviation (nullable = disabled)
 * @param zScoreThreshold           robust z-score (median/MAD) considered a deviation (nullable = disabled)
 * @param minRequests               windows with fewer requests are ignored (neither breach nor normal)
 * @param consecutiveWindows        consecutive breaching windows required for a WARNING anomaly
 * @param criticalConsecutiveWindows consecutive windows breaching the critical threshold required for CRITICAL
 * @param baselineWindows           how many older windows form the baseline
 * @param minBaselineWindows        below this many valid baseline windows, baseline/z-score checks are skipped
 *                                  (state INSUFFICIENT_BASELINE); static thresholds still apply
 * @param recoveryWindows           consecutive normal windows after which the series counts as recovered
 */
public record DetectionRule(
        String ruleKey,
        RuleMetric metric,
        String serviceName,
        boolean enabled,
        Double warningThreshold,
        Double criticalThreshold,
        Double baselineRatio,
        Double zScoreThreshold,
        int minRequests,
        int consecutiveWindows,
        int criticalConsecutiveWindows,
        int baselineWindows,
        int minBaselineWindows,
        int recoveryWindows) {}
