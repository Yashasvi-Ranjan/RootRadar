package com.rootradar.apifd.telemetry;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import com.rootradar.apifd.monitoring.TimeRange;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST: latency/error series, endpoint summaries and caller-measured dependency series (API_CONTRACTS section 2). */
@RestController
@RequestMapping("/api/services/{id}")
public class MetricsController {

    static final int MAX_POINTS = 2000;

    private final ServiceRegistry registry;
    private final MetricsQueryService metrics;
    private final Clock clock;
    private final int windowSeconds;

    public MetricsController(ServiceRegistry registry, MetricsQueryService metrics, Clock clock,
                             ApifdProperties props) {
        this.registry = registry;
        this.metrics = metrics;
        this.clock = clock;
        this.windowSeconds = MetricMath.windowSecondsOr(props.detection().windowSeconds());
    }

    @GetMapping("/metrics")
    public MetricsSeriesResponse series(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer bucketSeconds,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String endpoint) {
        MonitoredService s = require(id);
        TimeRange r = TimeRange.resolve(from, to, clock);
        int effective = checkPoints(r, bucketSeconds);
        boolean byEndpoint = endpoint != null && !endpoint.isBlank();
        MetricsSource source = s.nodeType() == NodeType.HTTP_SERVICE ? MetricsSource.INBOUND_TELEMETRY
                : MetricsSource.CALLER_MEASUREMENT;
        List<MetricPoint> pts = byEndpoint
                ? metrics.endpointSeries(id, method, endpoint, r.from(), r.to(), bucketSeconds)
                : metrics.serviceSeries(id, r.from(), r.to(), bucketSeconds);
        return new MetricsSeriesResponse(id, s.name(), byEndpoint ? MetricScope.ENDPOINT : MetricScope.SERVICE_INBOUND,
                source, byEndpoint ? method : null, byEndpoint ? endpoint : null, effective, pts);
    }

    @GetMapping("/endpoints")
    public List<EndpointSummary> endpoints(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        require(id);
        TimeRange r = TimeRange.resolve(from, to, clock);
        return metrics.endpointSummaries(id, r.from(), r.to());
    }

    @GetMapping("/dependencies/metrics")
    public DependencyMetricsResponse dependencies(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer bucketSeconds,
            @RequestParam(required = false) String dependency) {
        MonitoredService s = require(id);
        TimeRange r = TimeRange.resolve(from, to, clock);
        int effective = checkPoints(r, bucketSeconds);
        List<String> names = dependency != null && !dependency.isBlank() ? List.of(dependency)
                : List.copyOf(metrics.dependencySummaries(id, r.from(), r.to()).keySet());
        List<DependencyMetricsResponse.Dependency> deps = names.stream()
                .map(n -> new DependencyMetricsResponse.Dependency(n, effective,
                        metrics.dependencySeries(id, n, r.from(), r.to(), bucketSeconds)))
                .toList();
        return new DependencyMetricsResponse(id, s.name(), deps);
    }

    /** Validates the bucket and rejects ranges that would return more than {@value #MAX_POINTS} points. */
    private int checkPoints(TimeRange r, Integer bucketSeconds) {
        MetricMath.validateBucket(bucketSeconds, windowSeconds);
        int effective = bucketSeconds == null ? windowSeconds : bucketSeconds;
        long points = (Duration.between(r.from(), r.to()).toSeconds() + effective - 1) / effective;
        if (points > MAX_POINTS) {
            throw new ApiException.BadRequest("INVALID_RANGE", "Range would return about " + points
                    + " points (max " + MAX_POINTS + "); use a larger bucketSeconds");
        }
        return effective;
    }

    private MonitoredService require(long id) {
        return registry.findById(id).orElseThrow(() -> new ApiException.NotFound("Service " + id + " not found"));
    }
}
