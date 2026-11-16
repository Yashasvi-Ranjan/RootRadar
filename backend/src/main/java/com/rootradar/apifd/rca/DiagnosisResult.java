package com.rootradar.apifd.rca;

import java.time.Instant;
import java.util.List;

/**
 * Output of the RCA engine.
 * @param modelVersion        version tag of the scoring model, e.g. "rca-rules-1.0" (see docs/RCA_SCORING_MODEL.md)
 * @param probableCauseService name of the PROBABLE_CAUSE service, or null when no service reaches the documented
 *                             threshold (then conclusive=false)
 * @param topScore             score of findings.get(0), null if no findings
 * @param conclusive           false when evidence is insufficient or the top candidates are too close to separate
 * @param summary              1-3 sentences generated from the findings (no canned diagnosis)
 * @param findings             one per service involved in the anomaly/impact set, sorted by rank ascending
 * @param caveats              honest limitations, e.g. "ranking is correlation-based", "database has no direct health check"
 */
public record DiagnosisResult(
        Instant analyzedAt,
        String modelVersion,
        String probableCauseService,
        Integer topScore,
        boolean conclusive,
        String summary,
        List<ServiceFinding> findings,
        List<String> caveats) {}
