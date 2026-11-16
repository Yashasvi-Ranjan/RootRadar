package com.rootradar.apifd.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * JSON contract of {@code GET /internal/telemetry} exposed by every instrumented service
 * (see docs/API_CONTRACTS.md section 7.1). Parsed by the backend collector (Agent 2), produced by the demo services
 * (Agent 6) - both code against this exact shape. Unknown properties are ignored for forward compatibility.
 *
 * <p>Rules: windows are fixed-length (windowSeconds), aligned to epoch multiples, only COMPLETED windows are
 * returned, ordered oldest first, at most 60 retained in the service. {@code overall} is always present (count may be
 * 0, then latency fields are 0); endpoints/dependencies list only entries with count &gt; 0. Error = HTTP status
 * &gt;= 500 or exception for endpoints; for dependencies: non-2xx, IO error or timeout.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryReport(
        int schemaVersion,
        String service,
        Instant generatedAt,
        Instant startedAt,
        int windowSeconds,
        List<Window> windows) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Window(Instant windowStart, Instant windowEnd, Overall overall, List<EndpointStats> endpoints,
                         List<DependencyStats> dependencies) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Overall(long count, long errorCount, double avgMs, double p50Ms, double p95Ms, double p99Ms,
                          double maxMs) {}

    /** endpoint = route TEMPLATE (e.g. /api/orders/{id}), never the raw URL, to bound cardinality. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndpointStats(String method, String endpoint, long count, long errorCount, double avgMs,
                                double p50Ms, double p95Ms, double p99Ms, double maxMs) {}

    /** name = what the caller calls the dependency (matches service_dependencies.measurement_name); type HTTP | JDBC. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DependencyStats(String name, String type, long count, long errorCount, long timeoutCount,
                                  double avgMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {}
}
