package com.rootradar.apifd.telemetry;

import java.time.Instant;

/**
 * Aggregate over a time range. count/error/avg are exact (weighted by requestCount). p50/p95/p99 are
 * request-count-weighted means of the per-window percentiles (percentilesApproximate=true) because exact merged
 * percentiles cannot be recovered from windowed percentiles; maxMs is the exact maximum.
 */
public record MetricsSummary(
        Instant from, Instant to, MetricsSource source,
        long requestCount, long errorCount, double errorRate,
        double avgMs, double p50Ms, double p95Ms, double p99Ms, double maxMs,
        boolean percentilesApproximate) {}
