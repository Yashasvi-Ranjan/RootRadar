package com.rootradar.apifd.anomaly;

import java.time.Instant;
import java.util.List;

/** Output of one detector run. {@code assessments} has one entry per input series plus one per node with health samples (scope HEALTH_CHECK). */
public record DetectionResult(Instant evaluatedAt, List<AnomalyEvent> anomalies, List<SeriesAssessment> assessments) {}
