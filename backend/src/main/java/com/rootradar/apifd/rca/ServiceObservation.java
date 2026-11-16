package com.rootradar.apifd.rca;

import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.monitoring.HealthStatus;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.telemetry.MetricsSource;
import java.time.Instant;

/**
 * Current state of ONE graph node, including nodes that are perfectly healthy (a NORMAL database is evidence).
 * Built by Agent 5 from DetectionResult.assessments (SERVICE_INBOUND series for HTTP nodes; the DEPENDENCY_CALL
 * series for DEPENDENCY_ONLY nodes) and HealthQueryService. One observation per graph node.
 *
 * @param state                 verdict for the node's primary series (NO_DATA if none)
 * @param source                INBOUND_TELEMETRY, CALLER_MEASUREMENT or NONE
 * @param healthStatus          latest synthetic check (UNKNOWN for DEPENDENCY_ONLY)
 * @param currentLatencyP95Ms   latest valid window p95 (null if none)
 * @param baselineLatencyP95Ms  baseline median p95 (null if insufficient)
 * @param currentErrorRate      latest valid window error rate 0..1 (null if none)
 * @param baselineErrorRate     baseline median error rate (null if insufficient)
 * @param recentRequestCount    requests in the evaluation span
 * @param firstAbnormalAt       earliest firstBreachAt among the node's anomalies (null if none)
 */
public record ServiceObservation(
        String serviceName,
        NodeType nodeType,
        HealthStatus healthStatus,
        MetricsSource source,
        SeriesState state,
        Double currentLatencyP95Ms,
        Double baselineLatencyP95Ms,
        Double currentErrorRate,
        Double baselineErrorRate,
        long recentRequestCount,
        Instant firstAbnormalAt) {}
