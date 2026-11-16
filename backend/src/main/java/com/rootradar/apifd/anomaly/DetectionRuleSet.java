package com.rootradar.apifd.anomaly;

import java.util.List;
import java.util.Optional;

/** Immutable collection of rules with override resolution. Pure. */
public record DetectionRuleSet(List<DetectionRule> rules) {

    public DetectionRuleSet {
        rules = List.copyOf(rules);
    }

    /**
     * Service-specific enabled rule for the metric if present, otherwise the enabled global rule, otherwise empty
     * (meaning: do not evaluate this metric for this service).
     */
    public Optional<DetectionRule> resolve(String serviceName, RuleMetric metric) {
        Optional<DetectionRule> specific = rules.stream()
                .filter(r -> r.enabled() && r.metric() == metric && serviceName.equals(r.serviceName())).findFirst();
        if (specific.isPresent()) return specific;
        return rules.stream().filter(r -> r.enabled() && r.metric() == metric && r.serviceName() == null).findFirst();
    }
}
