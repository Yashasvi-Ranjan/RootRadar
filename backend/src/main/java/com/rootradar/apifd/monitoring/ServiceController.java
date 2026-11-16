package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.common.Constants;
import com.rootradar.apifd.common.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST: service registry, on-demand checks, health history and availability (API_CONTRACTS section 2). */
@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceRegistry registry;
    private final ServiceOverviewService overview;
    private final HealthCheckRunner runner;
    private final HealthQueryService health;
    private final Clock clock;

    public ServiceController(ServiceRegistry registry, ServiceOverviewService overview, HealthCheckRunner runner,
                             HealthQueryService health, Clock clock) {
        this.registry = registry;
        this.overview = overview;
        this.runner = runner;
        this.health = health;
        this.clock = clock;
    }

    @GetMapping
    public List<ServiceOverview> list() {
        return overview.list();
    }

    @PostMapping
    public ResponseEntity<MonitoredService> register(@Valid @RequestBody RegisterServiceRequest request) {
        MonitoredService created = registry.register(request);
        return ResponseEntity.created(URI.create("/api/services/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ServiceOverview get(@PathVariable long id) {
        return overview.get(id);
    }

    @PutMapping("/{id}")
    public MonitoredService update(@PathVariable long id, @Valid @RequestBody UpdateServiceRequest request) {
        return registry.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        registry.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/check")
    public HealthCheckResult check(@PathVariable long id) {
        return runner.runNow(id);
    }

    @GetMapping("/{id}/health")
    public PageResponse<HealthCheckResult> history(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Constants.DEFAULT_PAGE_SIZE) int size) {
        TimeRange r = TimeRange.resolve(from, to, clock);
        if (page < 0 || size < 1 || size > Constants.MAX_PAGE_SIZE) {
            throw new ApiException.BadRequest("BAD_REQUEST",
                    "page must be >= 0 and size between 1 and " + Constants.MAX_PAGE_SIZE);
        }
        return PageResponse.of(health.history(id, r.from(), r.to(), PageRequest.of(page, size)));
    }

    @GetMapping("/{id}/availability")
    public AvailabilityReport availability(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "5") int bucketMinutes) {
        TimeRange r = TimeRange.resolve(from, to, clock);
        return health.availability(id, r.from(), r.to(), bucketMinutes);
    }
}
