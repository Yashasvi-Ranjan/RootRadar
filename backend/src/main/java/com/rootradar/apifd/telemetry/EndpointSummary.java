package com.rootradar.apifd.telemetry;

/** Per-endpoint aggregate (same aggregation rules as {@link MetricsSummary}). */
public record EndpointSummary(String method, String endpoint, MetricsSummary summary) {}
