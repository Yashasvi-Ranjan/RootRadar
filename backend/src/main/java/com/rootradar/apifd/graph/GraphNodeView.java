package com.rootradar.apifd.graph;

import com.rootradar.apifd.monitoring.HealthStatus;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.telemetry.MetricsSummary;

/**
 * Node of the visualisation payload.
 * @param layer           0 = entry point (no callers), increasing along the longest path; for layout
 * @param healthStatus    latest synthetic health result (UNKNOWN for DEPENDENCY_ONLY)
 * @param recentMetrics   last-5-minutes summary (null when no traffic); source says INBOUND_TELEMETRY vs CALLER_MEASUREMENT.
 * Incident state is NOT included (avoids a graph->incident dependency); the frontend joins with GET /api/incidents/active
 */
public record GraphNodeView(long serviceId, String name, String displayName, NodeType nodeType, boolean demo,
                            int layer, HealthStatus healthStatus, MetricsSummary recentMetrics) {}
