package com.rootradar.apifd.telemetry;

import java.time.Instant;

/**
 * Aggregates of one telemetry window (or one re-bucketed interval). Plain immutable value, used by the pure engines
 * and by the REST layer. Latencies in milliseconds. For windows with requestCount == 0 the latency fields are 0 and
 * MUST be ignored by consumers (check {@link #hasTraffic()}).
 */
public record MetricPoint(
        Instant windowStart,
        Instant windowEnd,
        long requestCount,
        long errorCount,
        double avgMs,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double maxMs) {

    public boolean hasTraffic() {
        return requestCount > 0;
    }

    /** errorCount / requestCount, 0.0 when no traffic. */
    public double errorRate() {
        return requestCount == 0 ? 0.0 : (double) errorCount / requestCount;
    }
}
