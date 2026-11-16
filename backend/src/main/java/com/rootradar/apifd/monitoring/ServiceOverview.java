package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.telemetry.MetricsSummary;

/**
 * GET /api/services item. health = latest synthetic check (null for DEPENDENCY_ONLY / never checked);
 * metrics = last 5 minutes (null when no traffic); availability24h = uptime percent (null when no checks).
 */
public record ServiceOverview(MonitoredService service, HealthCheckResult health, MetricsSummary metrics,
                              Double availability24h) {}
