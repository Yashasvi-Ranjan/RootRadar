package com.rootradar.apifd.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read side of stored telemetry. Implemented by Agent 2 (bean {@code JpaMetricsQueryService}); consumed by the
 * detection pipeline (Agent 5) and REST controllers (Agent 2). All instants UTC. from inclusive, to exclusive,
 * applied to windowStart. Throws BadRequest("INVALID_RANGE") if from &gt;= to or range &gt; Constants.MAX_QUERY_RANGE.
 *
 * <p><b>How non-HTTP nodes get metrics.</b> For a node with nodeType DEPENDENCY_ONLY (the database) the "inbound"
 * series is synthesised from {@code dependency_measurements} rows whose
 * (caller_service_id, dependency_name) matches an edge (caller -> node) in service_dependencies. If several callers
 * measure the same node their windows are merged by windowStart (counts summed, avg weighted, percentiles =
 * count-weighted mean, max = max). MetricsSource is then CALLER_MEASUREMENT. For HTTP nodes the source is
 * INBOUND_TELEMETRY (telemetry_snapshots rows with endpoint='*').
 */
public interface MetricsQueryService {

    /**
     * Every series the detector must look at, in one call:
     * SERVICE_INBOUND for each enabled node (HTTP: inbound telemetry; DEPENDENCY_ONLY: caller measurements),
     * ENDPOINT for each endpoint seen in the range, DEPENDENCY_CALL for each (caller, dependency) edge measurement.
     * Series with no points in the range are omitted.
     */
    List<MetricSeries> loadAllSeries(Instant from, Instant to);

    /** Service-wide series. bucketSeconds null = raw windows; otherwise re-bucketed (multiple of window length). */
    List<MetricPoint> serviceSeries(long serviceId, Instant from, Instant to, Integer bucketSeconds);

    /** One endpoint of an HTTP node. */
    List<MetricPoint> endpointSeries(long serviceId, String method, String endpoint, Instant from, Instant to,
                                     Integer bucketSeconds);

    /** Calls measured BY serviceId (the caller) towards dependencyName. */
    List<MetricPoint> dependencySeries(long callerServiceId, String dependencyName, Instant from, Instant to,
                                       Integer bucketSeconds);

    /** Empty when no traffic windows exist in the range. */
    Optional<MetricsSummary> serviceSummary(long serviceId, Instant from, Instant to);

    List<EndpointSummary> endpointSummaries(long serviceId, Instant from, Instant to);

    /** Summary per dependency measured by this caller. Key = dependency name. */
    java.util.Map<String, MetricsSummary> dependencySummaries(long callerServiceId, Instant from, Instant to);
}
