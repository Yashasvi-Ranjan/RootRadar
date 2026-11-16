package com.rootradar.apifd.monitoring;

import java.time.Instant;

/** One synthetic health check (row of health_check_results). JSON shape identical. */
public record HealthCheckResult(
        long id,
        long serviceId,
        String serviceName,
        Instant checkedAt,
        HealthStatus status,
        HealthOutcome outcome,
        Integer httpStatus,
        Integer responseTimeMs,
        String errorMessage) {}
