package com.rootradar.apifd.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read side of health checks. Implemented by Agent 2 (bean {@code HealthQueryServiceImpl}).
 * The pipeline (Agent 5) uses {@link #recentSamples}; controllers (Agent 2) use the rest.
 * DEPENDENCY_ONLY nodes have no checks: latest() is empty, status is UNKNOWN.
 */
public interface HealthQueryService {

    /** Latest result per node id that has at least one check. */
    Map<Long, HealthCheckResult> latestForAll();

    Optional<HealthCheckResult> latest(long serviceId);

    /** Newest first. from/to nullable (open-ended). */
    Page<HealthCheckResult> history(long serviceId, Instant from, Instant to, Pageable pageable);

    /** bucketMinutes in [1, 1440]. Throws BadRequest("INVALID_RANGE") if range &gt; Constants.MAX_QUERY_RANGE. */
    AvailabilityReport availability(long serviceId, Instant from, Instant to, int bucketMinutes);

    /** Newest-last samples with checkedAt &gt;= since for every HTTP node; used by the consecutive-failure rule. */
    List<HealthSample> recentSamples(Instant since);
}
