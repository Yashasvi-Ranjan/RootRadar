package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.monitoring.persistence.HealthCheckEntity;
import com.rootradar.apifd.monitoring.persistence.HealthCheckRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of health checks (history, latest, availability buckets, detector samples). */
@Service
@Transactional(readOnly = true)
public class HealthQueryServiceImpl implements HealthQueryService {

    static final int MAX_BUCKETS = 2000;

    private final HealthCheckRepository repo;
    private final ServiceRegistry registry;
    private final JdbcTemplate jdbc;

    public HealthQueryServiceImpl(HealthCheckRepository repo, ServiceRegistry registry, JdbcTemplate jdbc) {
        this.repo = repo;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, HealthCheckResult> latestForAll() {
        Map<Long, String> names = registry.findAll().stream()
                .collect(Collectors.toMap(MonitoredService::id, MonitoredService::name));
        Map<Long, HealthCheckResult> out = new LinkedHashMap<>();
        for (HealthCheckEntity e : repo.findLatestPerService()) {
            String name = names.get(e.getServiceId());
            if (name != null) out.put(e.getServiceId(), HttpHealthCheckRunner.toResult(e, name));
        }
        return out;
    }

    @Override
    public Optional<HealthCheckResult> latest(long serviceId) {
        return registry.findById(serviceId).flatMap(s ->
                repo.findFirstByServiceIdOrderByCheckedAtDescIdDesc(serviceId)
                        .map(e -> HttpHealthCheckRunner.toResult(e, s.name())));
    }

    @Override
    public Page<HealthCheckResult> history(long serviceId, Instant from, Instant to, Pageable pageable) {
        MonitoredService s = require(serviceId);
        if (from != null && to != null) TimeRange.validate(from, to);
        Specification<HealthCheckEntity> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("serviceId"), serviceId));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("checkedAt"), from));
            if (to != null) p.add(cb.lessThan(root.get("checkedAt"), to));
            return cb.and(p.toArray(Predicate[]::new));
        };
        Pageable fixed = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("checkedAt"), Sort.Order.desc("id")));
        return repo.findAll(spec, fixed).map(e -> HttpHealthCheckRunner.toResult(e, s.name()));
    }

    @Override
    public AvailabilityReport availability(long serviceId, Instant from, Instant to, int bucketMinutes) {
        MonitoredService s = require(serviceId);
        TimeRange.validate(from, to);
        if (bucketMinutes < 1 || bucketMinutes > 1440) {
            throw new ApiException.BadRequest("BAD_REQUEST", "bucketMinutes must be between 1 and 1440");
        }
        long bucketSec = bucketMinutes * 60L;
        long span = Duration.between(from, to).toSeconds();
        long n = (span + bucketSec - 1) / bucketSec;
        if (n > MAX_BUCKETS) {
            throw new ApiException.BadRequest("INVALID_RANGE",
                    "Range would produce " + n + " buckets (max " + MAX_BUCKETS + "); use a larger bucketMinutes");
        }
        Map<Long, long[]> agg = new HashMap<>(); // bucket -> total, failed, rtCount ; plus rtSum kept separately
        Map<Long, Double> rtSum = new HashMap<>();
        if (s.nodeType() == NodeType.HTTP_SERVICE) {
            jdbc.query("""
                    select cast(floor(extract(epoch from (checked_at - cast(? as timestamptz))) / ?) as bigint) as b,
                           count(*) as total,
                           count(*) filter (where status = 'DOWN') as failed,
                           coalesce(sum(response_time_ms), 0) as rt_sum,
                           count(response_time_ms) as rt_cnt
                    from health_check_results
                    where service_id = ? and checked_at >= cast(? as timestamptz) and checked_at < cast(? as timestamptz)
                    group by b order by b
                    """, rs -> {
                long b = rs.getLong("b");
                agg.put(b, new long[]{rs.getLong("total"), rs.getLong("failed"), rs.getLong("rt_cnt")});
                rtSum.put(b, rs.getDouble("rt_sum"));
            }, from.atOffset(ZoneOffset.UTC), bucketSec, serviceId, from.atOffset(ZoneOffset.UTC),
                    to.atOffset(ZoneOffset.UTC));
        }
        List<AvailabilityReport.Bucket> buckets = new ArrayList<>();
        long total = 0, failed = 0, rtCnt = 0;
        double rtTotal = 0;
        for (long i = 0; i < n; i++) {
            Instant start = from.plusSeconds(i * bucketSec);
            Instant end = start.plusSeconds(bucketSec).isAfter(to) ? to : start.plusSeconds(bucketSec);
            long[] a = agg.get(i);
            if (a == null) {
                buckets.add(new AvailabilityReport.Bucket(start, end, 0, 0, null, null));
                continue;
            }
            total += a[0];
            failed += a[1];
            rtCnt += a[2];
            rtTotal += rtSum.get(i);
            buckets.add(new AvailabilityReport.Bucket(start, end, a[0], a[1], uptime(a[0], a[1]),
                    a[2] == 0 ? null : rtSum.get(i) / a[2]));
        }
        return new AvailabilityReport(s.id(), s.name(), from, to, total, failed, uptime(total, failed),
                rtCnt == 0 ? null : rtTotal / rtCnt, buckets);
    }

    @Override
    public List<HealthSample> recentSamples(Instant since) {
        return jdbc.query("""
                select s.name, h.checked_at, h.status
                from health_check_results h join services s on s.id = h.service_id
                where s.node_type = 'HTTP_SERVICE' and h.checked_at >= cast(? as timestamptz)
                order by h.checked_at, h.id
                """, (rs, i) -> new HealthSample(rs.getString("name"),
                rs.getObject("checked_at", java.time.OffsetDateTime.class).toInstant(),
                "UP".equals(rs.getString("status"))), since.atOffset(ZoneOffset.UTC));
    }

    private static Double uptime(long total, long failed) {
        return total == 0 ? null : 100.0 * (total - failed) / total;
    }

    private MonitoredService require(long id) {
        return registry.findById(id).orElseThrow(() -> new ApiException.NotFound("Service " + id + " not found"));
    }
}
