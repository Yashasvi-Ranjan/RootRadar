package com.rootradar.apifd.telemetry;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import com.rootradar.apifd.monitoring.TimeRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL-backed {@link MetricsQueryService}. HTTP nodes read telemetry_snapshots; DEPENDENCY_ONLY nodes (database) read
 * dependency_measurements joined through service_dependencies(from=caller, measurement_name) so their metrics are the
 * ones their callers measured (source CALLER_MEASUREMENT).
 */
@Service
@Transactional(readOnly = true)
public class JpaMetricsQueryService implements MetricsQueryService {

    private static final String SNAP_COLS =
            "t.window_start, t.window_end, t.request_count, t.error_count, t.avg_ms, t.p50_ms, t.p95_ms, t.p99_ms, t.max_ms";
    private static final String DEP_COLS =
            "d.window_start, d.window_end, d.request_count, d.error_count, d.avg_ms, d.p50_ms, d.p95_ms, d.p99_ms, d.max_ms";
    private static final String AGG = """
            coalesce(sum(%1$s.request_count), 0) c, coalesce(sum(%1$s.error_count), 0) e,
            sum(%1$s.avg_ms * %1$s.request_count) a, sum(%1$s.p50_ms * %1$s.request_count) p50,
            sum(%1$s.p95_ms * %1$s.request_count) p95, sum(%1$s.p99_ms * %1$s.request_count) p99, max(%1$s.max_ms) mx""";
    private static final String DEPONLY_JOIN = """
            from dependency_measurements d
            join service_dependencies e on e.from_service_id = d.caller_service_id and e.measurement_name = d.dependency_name
            """;
    private static final String RANGE_T = " t.window_start >= ? and t.window_start < ? ";
    private static final String RANGE_D = " d.window_start >= ? and d.window_start < ? ";

    private static final RowMapper<MetricPoint> POINT = (rs, i) -> point(rs);

    private final JdbcTemplate jdbc;
    private final ServiceRegistry registry;
    private final int windowSeconds;

    public JpaMetricsQueryService(JdbcTemplate jdbc, ServiceRegistry registry, ApifdProperties props) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.windowSeconds = MetricMath.windowSecondsOr(props.detection().windowSeconds());
    }

    @Override
    public List<MetricSeries> loadAllSeries(Instant from, Instant to) {
        TimeRange.validate(from, to);
        OffsetDateTime f = utc(from), t = utc(to);
        Map<SeriesKey, List<MetricPoint>> byKey = new LinkedHashMap<>();

        jdbc.query("select s.name, t.method, t.endpoint, " + SNAP_COLS + " " + """
                 from telemetry_snapshots t join services s on s.id = t.service_id
                 where s.enabled and s.node_type = 'HTTP_SERVICE' and""" + RANGE_T + """
                 order by s.id, t.endpoint, t.method, t.window_start
                """, rs -> {
            String endpoint = rs.getString("endpoint");
            SeriesKey key = "*".equals(endpoint) ? SeriesKey.inbound(rs.getString("name"))
                    : SeriesKey.endpoint(rs.getString("name"), rs.getString("method"), endpoint);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(point(rs));
        }, f, t);

        Map<String, List<MetricPoint>> depOnlyInbound = new LinkedHashMap<>();
        jdbc.query("select caller.name caller_name, d.dependency_name, target.name target_name, target.node_type target_type, "
                + DEP_COLS + " " + DEPONLY_JOIN + """
                 join services caller on caller.id = d.caller_service_id
                 join services target on target.id = e.to_service_id
                 where caller.enabled and target.enabled and""" + RANGE_D + """
                 order by d.caller_service_id, d.dependency_name, d.window_start
                """, rs -> {
            MetricPoint p = point(rs);
            SeriesKey key = SeriesKey.dependency(rs.getString("target_name"), rs.getString("caller_name"),
                    rs.getString("dependency_name"));
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            if (NodeType.DEPENDENCY_ONLY.name().equals(rs.getString("target_type"))) {
                depOnlyInbound.computeIfAbsent(rs.getString("target_name"), k -> new ArrayList<>()).add(p);
            }
        }, f, t);

        depOnlyInbound.forEach((name, pts) -> {
            pts.sort((a, b) -> a.windowStart().compareTo(b.windowStart()));
            byKey.put(SeriesKey.inbound(name), MetricMath.mergeByWindow(pts));
        });
        List<MetricSeries> out = new ArrayList<>(byKey.size());
        byKey.forEach((k, v) -> { if (!v.isEmpty()) out.add(new MetricSeries(k, List.copyOf(v))); });
        return out;
    }

    @Override
    public List<MetricPoint> serviceSeries(long serviceId, Instant from, Instant to, Integer bucketSeconds) {
        TimeRange.validate(from, to);
        MetricMath.validateBucket(bucketSeconds, windowSeconds);
        MonitoredService s = require(serviceId);
        List<MetricPoint> pts;
        if (s.nodeType() == NodeType.HTTP_SERVICE) {
            pts = jdbc.query("select " + SNAP_COLS + " " + """
                     from telemetry_snapshots t
                     where t.service_id = ? and t.endpoint = '*' and t.method = '*' and""" + RANGE_T
                    + "order by t.window_start", POINT, serviceId, utc(from), utc(to));
        } else {
            pts = MetricMath.mergeByWindow(jdbc.query("select " + DEP_COLS + " " + DEPONLY_JOIN
                    + " where e.to_service_id = ? and" + RANGE_D + "order by d.window_start", POINT,
                    serviceId, utc(from), utc(to)));
        }
        return MetricMath.rebucket(pts, bucketSeconds);
    }

    @Override
    public List<MetricPoint> endpointSeries(long serviceId, String method, String endpoint, Instant from, Instant to,
                                            Integer bucketSeconds) {
        TimeRange.validate(from, to);
        MetricMath.validateBucket(bucketSeconds, windowSeconds);
        MonitoredService s = require(serviceId);
        if (s.nodeType() != NodeType.HTTP_SERVICE || endpoint == null || endpoint.isBlank()) return List.of();
        List<Object> args = new ArrayList<>(List.of(serviceId, endpoint));
        String methodSql = "";
        if (method != null && !method.isBlank()) {
            methodSql = " and t.method = ?";
            args.add(method.toUpperCase());
        }
        args.add(utc(from));
        args.add(utc(to));
        List<MetricPoint> pts = jdbc.query("select " + SNAP_COLS
                + " from telemetry_snapshots t where t.service_id = ? and t.endpoint = ?" + methodSql + " and"
                + RANGE_T + "order by t.window_start", POINT, args.toArray());
        return MetricMath.rebucket(MetricMath.mergeByWindow(pts), bucketSeconds);
    }

    @Override
    public List<MetricPoint> dependencySeries(long callerServiceId, String dependencyName, Instant from, Instant to,
                                              Integer bucketSeconds) {
        TimeRange.validate(from, to);
        MetricMath.validateBucket(bucketSeconds, windowSeconds);
        require(callerServiceId);
        List<MetricPoint> pts = jdbc.query("select " + DEP_COLS
                + " from dependency_measurements d where d.caller_service_id = ? and d.dependency_name = ? and"
                + RANGE_D + "order by d.window_start", POINT, callerServiceId, dependencyName, utc(from), utc(to));
        return MetricMath.rebucket(pts, bucketSeconds);
    }

    @Override
    public Optional<MetricsSummary> serviceSummary(long serviceId, Instant from, Instant to) {
        TimeRange.validate(from, to);
        MonitoredService s = require(serviceId);
        if (s.nodeType() == NodeType.HTTP_SERVICE) {
            List<MetricsSummary> r = jdbc.query("select " + AGG.formatted("t")
                    + " from telemetry_snapshots t where t.service_id = ? and t.endpoint = '*' and t.method = '*'"
                    + " and t.request_count > 0 and" + RANGE_T,
                    (rs, i) -> summary(rs, from, to, MetricsSource.INBOUND_TELEMETRY), serviceId, utc(from), utc(to));
            return r.stream().filter(x -> x != null).findFirst();
        }
        // DEPENDENCY_ONLY: merge callers per window first so counts are not double-weighted incorrectly (sums are associative).
        List<MetricsSummary> r = jdbc.query("select " + AGG.formatted("d") + " " + DEPONLY_JOIN
                + " where e.to_service_id = ? and d.request_count > 0 and" + RANGE_D,
                (rs, i) -> summary(rs, from, to, MetricsSource.CALLER_MEASUREMENT), serviceId, utc(from), utc(to));
        return r.stream().filter(x -> x != null).findFirst();
    }

    @Override
    public List<EndpointSummary> endpointSummaries(long serviceId, Instant from, Instant to) {
        TimeRange.validate(from, to);
        MonitoredService s = require(serviceId);
        if (s.nodeType() != NodeType.HTTP_SERVICE) return List.of();
        List<EndpointSummary> out = new ArrayList<>();
        jdbc.query("select t.method, t.endpoint, " + AGG.formatted("t")
                + " from telemetry_snapshots t where t.service_id = ? and t.endpoint <> '*' and t.request_count > 0"
                + " and" + RANGE_T + "group by t.method, t.endpoint order by t.endpoint, t.method",
                rs -> {
                    MetricsSummary m = summary(rs, from, to, MetricsSource.INBOUND_TELEMETRY);
                    if (m != null) out.add(new EndpointSummary(rs.getString("method"), rs.getString("endpoint"), m));
                }, serviceId, utc(from), utc(to));
        return out;
    }

    @Override
    public Map<String, MetricsSummary> dependencySummaries(long callerServiceId, Instant from, Instant to) {
        TimeRange.validate(from, to);
        require(callerServiceId);
        Map<String, MetricsSummary> out = new LinkedHashMap<>();
        jdbc.query("select d.dependency_name, " + AGG.formatted("d")
                + " from dependency_measurements d where d.caller_service_id = ? and d.request_count > 0 and"
                + RANGE_D + "group by d.dependency_name order by d.dependency_name",
                rs -> {
                    MetricsSummary m = summary(rs, from, to, MetricsSource.CALLER_MEASUREMENT);
                    if (m != null) out.put(rs.getString("dependency_name"), m);
                }, callerServiceId, utc(from), utc(to));
        return out;
    }

    private MonitoredService require(long id) {
        return registry.findById(id).orElseThrow(() -> new ApiException.NotFound("Service " + id + " not found"));
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    private static MetricPoint point(ResultSet rs) throws SQLException {
        return new MetricPoint(rs.getObject("window_start", OffsetDateTime.class).toInstant(),
                rs.getObject("window_end", OffsetDateTime.class).toInstant(), rs.getLong("request_count"),
                rs.getLong("error_count"), rs.getDouble("avg_ms"), rs.getDouble("p50_ms"), rs.getDouble("p95_ms"),
                rs.getDouble("p99_ms"), rs.getDouble("max_ms"));
    }

    private static MetricsSummary summary(ResultSet rs, Instant from, Instant to, MetricsSource source)
            throws SQLException {
        long c = rs.getLong("c");
        if (c == 0) return null;
        long e = rs.getLong("e");
        return new MetricsSummary(from, to, source, c, e, (double) e / c, rs.getDouble("a") / c,
                rs.getDouble("p50") / c, rs.getDouble("p95") / c, rs.getDouble("p99") / c, rs.getDouble("mx"), true);
    }
}
