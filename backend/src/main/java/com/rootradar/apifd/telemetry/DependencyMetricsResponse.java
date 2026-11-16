package com.rootradar.apifd.telemetry;

import java.util.List;

/** GET /api/services/{id}/dependencies/metrics: calls measured BY the caller towards each dependency. */
public record DependencyMetricsResponse(long callerServiceId, String callerName, List<Dependency> dependencies) {

    public record Dependency(String name, int bucketSeconds, List<MetricPoint> points) {}
}
