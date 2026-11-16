package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.monitoring.persistence.HealthCheckEntity;
import com.rootradar.apifd.monitoring.persistence.HealthCheckRepository;
import com.rootradar.apifd.realtime.EventPublisher;
import com.rootradar.apifd.realtime.LiveEvent;
import com.rootradar.apifd.realtime.LiveEventType;
import com.rootradar.apifd.realtime.LiveEvents;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes one synthetic health check over {@link SafeHttpClient} and persists + publishes the result.
 * Deliberately NOT transactional: no DB connection is held while waiting for the remote service.
 * 2xx within the timeout = UP/SUCCESS; non-2xx = DOWN/HTTP_ERROR; timeout, refused/DNS, SSRF block and oversized
 * body map to TIMEOUT, CONNECTION_FAILED, BLOCKED, INVALID_RESPONSE. Network problems never throw.
 */
@Component
public class HttpHealthCheckRunner implements HealthCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(HttpHealthCheckRunner.class);

    private final ServiceRegistry registry;
    private final SafeHttpClient http;
    private final HealthCheckRepository repo;
    private final EventPublisher events;
    private final Clock clock;

    public HttpHealthCheckRunner(ServiceRegistry registry, SafeHttpClient http, HealthCheckRepository repo,
                                 EventPublisher events, Clock clock) {
        this.registry = registry;
        this.http = http;
        this.repo = repo;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public HealthCheckResult runNow(long serviceId) {
        MonitoredService s = registry.findById(serviceId)
                .orElseThrow(() -> new ApiException.NotFound("Service " + serviceId + " not found"));
        if (s.nodeType() != NodeType.HTTP_SERVICE) {
            throw new ApiException.BadRequest("BAD_REQUEST",
                    "Node '" + s.name() + "' is DEPENDENCY_ONLY and has no health endpoint");
        }
        Instant checkedAt = clock.instant();
        HealthCheckEntity row;
        try {
            SafeHttpClient.Response r = http.get(s.baseUrl(), s.healthEndpoint(), s.timeoutMs());
            boolean ok = r.status() >= 200 && r.status() < 300;
            row = new HealthCheckEntity(s.id(), checkedAt, ok ? HealthStatus.UP : HealthStatus.DOWN,
                    ok ? HealthOutcome.SUCCESS : HealthOutcome.HTTP_ERROR, r.status(), (int) r.elapsedMs(),
                    ok ? null : "HTTP " + r.status());
        } catch (SafeHttpClient.FetchException e) {
            row = new HealthCheckEntity(s.id(), checkedAt, HealthStatus.DOWN, e.outcome(), e.httpStatus(),
                    e.outcome() == HealthOutcome.BLOCKED ? null : (int) e.elapsedMs(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Unexpected failure while checking {}: {}", s.name(), e.toString());
            row = new HealthCheckEntity(s.id(), checkedAt, HealthStatus.DOWN, HealthOutcome.CONNECTION_FAILED, null,
                    null, "Unexpected error: " + e.getClass().getSimpleName());
        }
        HealthCheckEntity saved = repo.save(row);
        HealthCheckResult result = toResult(saved, s.name());
        try {
            events.publish(new LiveEvent(LiveEventType.HEALTH_UPDATED, checkedAt,
                    new LiveEvents.HealthUpdated(s.id(), s.name(), result)));
        } catch (RuntimeException e) {
            log.debug("Publishing health.updated failed: {}", e.toString());
        }
        return result;
    }

    static HealthCheckResult toResult(HealthCheckEntity e, String serviceName) {
        return new HealthCheckResult(e.getId(), e.getServiceId(), serviceName, e.getCheckedAt(), e.getStatus(),
                e.getOutcome(), e.getHttpStatus(), e.getResponseTimeMs(), e.getErrorMessage());
    }
}
