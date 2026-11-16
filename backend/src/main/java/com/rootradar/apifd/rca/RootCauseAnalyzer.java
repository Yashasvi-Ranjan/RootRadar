package com.rootradar.apifd.rca;

/**
 * Pure, rule-based, explainable root-cause engine (Agent 4). Implementation class:
 * {@code com.rootradar.apifd.rca.engine.RuleBasedRootCauseAnalyzer}.
 *
 * <p>Contract:
 * <ul>
 *   <li>PURE and deterministic (same input -> same output); no Spring beans/repositories/clock. May carry
 *       {@code @Component} with a no-arg constructor.</li>
 *   <li>Uses only {@link DiagnosisInput}: which service first showed abnormal behaviour (firstBreachAt),
 *       relative latency increase (current vs baseline), error-rate change, dependency relations (is the abnormal
 *       service's own downstream healthy?), downstream failures, upstream impact (graph.upstream()).</li>
 *   <li>A service whose downstream dependency is abnormal is a CONTRIBUTING_FACTOR/AFFECTED, not the cause; a
 *       service abnormal while all of its downstream nodes are NORMAL is the strongest cause candidate.</li>
 *   <li>Every score is the sum of documented {@link ScoreFactor}s, clamped to 0..100, with weights justified in
 *       docs/RCA_SCORING_MODEL.md. No random/arbitrary percentages, no calibrated-probability claims.</li>
 *   <li>Handles: no anomalies (empty findings, conclusive=false), insufficient baseline (INSUFFICIENT_EVIDENCE),
 *       ties (conclusive=false when top two are within the documented margin), unknown service names.</li>
 * </ul>
 */
public interface RootCauseAnalyzer {
    DiagnosisResult analyze(DiagnosisInput input);
}
