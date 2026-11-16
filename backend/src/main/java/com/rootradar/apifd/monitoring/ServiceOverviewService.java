package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.telemetry.MetricsQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Assembles {@link ServiceOverview} (registry + latest health + 5-min metrics + 24 h availability). */
@Service
public class ServiceOverviewService {

    private final ServiceRegistry registry;
    private final HealthQueryService health;
    private final MetricsQueryService metrics;
    private final Clock clock;

    public ServiceOverviewService(ServiceRegistry registry, HealthQueryService health, MetricsQueryService metrics,
                                  Clock clock) {
        this.registry = registry;
        this.health = health;
        this.metrics = metrics;
        this.clock = clock;
    }

    public List<ServiceOverview> list() {
        Map<Long, HealthCheckResult> latest = health.latestForAll();
        Instant now = clock.instant();
        return registry.findAll().stream().map(s -> build(s, latest.get(s.id()), now)).toList();
    }

    public ServiceOverview get(long id) {
        MonitoredService s = registry.findById(id)
                .orElseThrow(() -> new ApiException.NotFound("Service " + id + " not found"));
        return build(s, health.latest(id).orElse(null), clock.instant());
    }

    private ServiceOverview build(MonitoredService s, HealthCheckResult latest, Instant now) {
        var summary = metrics.serviceSummary(s.id(), now.minus(Duration.ofMinutes(5)), now).orElse(null);
        Double avail = null;
        if (s.nodeType() == NodeType.HTTP_SERVICE && latest != null) {
            avail = health.availability(s.id(), now.minus(Duration.ofHours(24)), now, 1440).uptimePercent();
        }
        return new ServiceOverview(s, latest, summary, avail);
    }
}
