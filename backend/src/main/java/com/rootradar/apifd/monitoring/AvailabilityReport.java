package com.rootradar.apifd.monitoring;

import java.time.Instant;
import java.util.List;

/** Availability history of one node over [from,to). uptimePercent = 100 * UP checks / total checks (null if 0 checks). */
public record AvailabilityReport(
        long serviceId, String serviceName, Instant from, Instant to,
        long totalChecks, long failedChecks, Double uptimePercent, Double avgResponseTimeMs,
        List<Bucket> buckets) {

    /** Equal-width time bucket; uptimePercent is null when the bucket has no checks. */
    public record Bucket(Instant start, Instant end, long totalChecks, long failedChecks, Double uptimePercent,
                         Double avgResponseTimeMs) {}
}
