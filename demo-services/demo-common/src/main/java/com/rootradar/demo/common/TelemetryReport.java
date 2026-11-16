package com.rootradar.demo.common;

import java.time.Instant;
import java.util.List;

/** JSON shape of GET /internal/telemetry (docs/API_CONTRACTS.md 7.1). Mirrors backend TelemetryReport. */
public record TelemetryReport(int schemaVersion, String service, Instant generatedAt, Instant startedAt,
                              int windowSeconds, List<Window> windows) {

    public record Window(Instant windowStart, Instant windowEnd, Overall overall, List<EndpointStats> endpoints,
                         List<DependencyStats> dependencies) {}

    public record Overall(long count, long errorCount, double avgMs, double p50Ms, double p95Ms, double p99Ms,
                          double maxMs) {}

    public record EndpointStats(String method, String endpoint, long count, long errorCount, double avgMs,
                                double p50Ms, double p95Ms, double p99Ms, double maxMs) {}

    public record DependencyStats(String name, String type, long count, long errorCount, long timeoutCount,
                                  double avgMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {}
}
