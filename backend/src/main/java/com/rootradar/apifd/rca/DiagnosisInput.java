package com.rootradar.apifd.rca;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.graph.DependencyGraph;
import java.time.Instant;
import java.util.List;

/**
 * Plain input of the RCA engine. {@code anomalies} are the CURRENTLY OPEN anomalies of the incident (all scopes,
 * including DEPENDENCY_CALL anomalies observed by callers). {@code observations} has one entry per graph node.
 */
public record DiagnosisInput(Instant analyzedAt, DependencyGraph graph, List<AnomalyEvent> anomalies,
                             List<ServiceObservation> observations) {}
