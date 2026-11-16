package com.rootradar.apifd.anomaly;

/**
 * Supplies the current rules to the pipeline. Implemented by Agent 3 in package {@code anomaly.rules}
 * (bean {@code DetectionRuleService}, reads detection_rules, resolving service_id to service name).
 */
public interface DetectionRuleProvider {
    DetectionRuleSet currentRules();
}
