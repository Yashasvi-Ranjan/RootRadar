package com.rootradar.apifd.monitoring;

/**
 * Executes one health check immediately (also used by POST /api/services/{id}/check).
 * Implemented by Agent 2 (bean {@code HttpHealthCheckRunner}, java.net.http.HttpClient, bounded executor).
 * Never throws for network problems: they are returned as DOWN results with the matching {@link HealthOutcome}.
 * Persists the result and publishes {@code health.updated} through EventPublisher.
 */
public interface HealthCheckRunner {
    HealthCheckResult runNow(long serviceId);
}
