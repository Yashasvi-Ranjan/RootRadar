package com.rootradar.apifd.rca;

import java.time.Instant;
import java.util.List;

/**
 * RCA verdict for one service.
 * @param rank              1 = highest score; ties broken deterministically (documented in docs/RCA_SCORING_MODEL.md)
 * @param score             explainable ranking score 0..100 = clamp(sum of factor points). NOT a probability.
 * @param firstAbnormalAt   when this service first looked abnormal in the incident window (null if never)
 * @param factors           full breakdown, in display order; sum(points) clamped to 0..100 equals score
 * @param evidence          human-readable evidence sentences derived from real measurements (never canned text)
 */
public record ServiceFinding(
        String serviceName,
        int rank,
        FindingClassification classification,
        int score,
        Instant firstAbnormalAt,
        List<ScoreFactor> factors,
        List<String> evidence) {}
