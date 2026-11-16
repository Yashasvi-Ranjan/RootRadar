package com.rootradar.apifd.telemetry;

import java.util.List;

/** GET /api/services/{id}/metrics. bucketSeconds = effective bucket (raw window length when none requested). */
public record MetricsSeriesResponse(long serviceId, String serviceName, MetricScope scope, MetricsSource source,
                                    String method, String endpoint, int bucketSeconds, List<MetricPoint> points) {}
