package com.rootradar.apifd.anomaly.store;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.anomaly.AnomalyEventView;
import com.rootradar.apifd.anomaly.AnomalyType;
import com.rootradar.apifd.anomaly.DetectionMethod;
import com.rootradar.apifd.anomaly.store.persistence.AnomalyEventEntity;
import com.rootradar.apifd.anomaly.store.persistence.AnomalyEventRepository;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.telemetry.MetricScope;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence of {@code anomaly_events}. The detector re-emits every ongoing anomaly on every evaluation, so rows are
 * de-duplicated by fingerprint: a firing fingerprint inserts one OPEN row and refreshes it afterwards; a fingerprint
 * that stops firing is marked CLEARED. Methods join the caller's transaction.
 */
@Component
public class AnomalyStore {
    private static final Logger log = LoggerFactory.getLogger(AnomalyStore.class);

    public enum Kind { CREATED, SEVERITY_CHANGED, REFRESHED, CLEARED }

    /** One persisted outcome; {@code entity} is the managed row. */
    public record Change(Kind kind, AnomalyEventEntity entity) {}

    private final AnomalyEventRepository repository;

    public AnomalyStore(AnomalyEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Upserts the firing anomalies and clears OPEN rows that no longer fire.
     * @return one Change per firing anomaly (CREATED / SEVERITY_CHANGED / REFRESHED) followed by the CLEARED ones
     */
    @Transactional
    public List<Change> sync(List<AnomalyEvent> firing, Instant now, ServiceLookup.Snapshot services) {
        Map<String, AnomalyEvent> byFingerprint = new LinkedHashMap<>();
        for (AnomalyEvent e : firing) {
            byFingerprint.merge(e.fingerprint(), e,
                    (a, b) -> b.severity().isAtLeast(a.severity()) && b.severity() != a.severity() ? b : a);
        }
        Map<String, AnomalyEventEntity> open = new HashMap<>();
        List<AnomalyEventEntity> duplicates = new ArrayList<>();
        for (AnomalyEventEntity row : repository.findByStatus(AnomalyEventEntity.OPEN)) {
            AnomalyEventEntity previous = open.put(row.fingerprint, row);
            if (previous != null) {
                // Should not happen; keep the newest row, close the other one.
                if (previous.id > row.id) open.put(row.fingerprint, previous);
                duplicates.add(previous.id > row.id ? row : previous);
            }
        }
        List<Change> changes = new ArrayList<>();
        for (AnomalyEvent e : byFingerprint.values()) {
            Long subjectId = services.idOf(e.subjectService()).orElse(null);
            if (subjectId == null) {
                log.warn("Ignoring anomaly {}: unknown service '{}'", e.fingerprint(), e.subjectService());
                continue;
            }
            AnomalyEventEntity row = open.get(e.fingerprint());
            if (row == null) {
                row = new AnomalyEventEntity();
                row.fingerprint = e.fingerprint();
                row.status = AnomalyEventEntity.OPEN;
                row.detectedAt = e.detectedAt() != null ? e.detectedAt() : now;
                row.firstBreachAt = e.firstBreachAt() != null ? e.firstBreachAt() : row.detectedAt;
                row.subjectServiceId = subjectId;
                row.observedByServiceId = services.idOf(e.observedBy()).orElse(null);
                row.anomalyType = e.type().name();
                row.scope = e.scope().name();
                row.endpointMethod = e.method();
                row.endpoint = e.endpoint();
                row.dependencyName = e.dependencyName();
                row.occurrenceCount = 1;
                copyMeasurements(row, e);
                row.lastSeenAt = now;
                row = repository.save(row);
                changes.add(new Change(Kind.CREATED, row));
            } else {
                boolean severityChanged = !row.severity.equals(e.severity().name());
                row.occurrenceCount++;
                row.lastSeenAt = now;
                if (e.firstBreachAt() != null && e.firstBreachAt().isBefore(row.firstBreachAt)) {
                    row.firstBreachAt = e.firstBreachAt();
                }
                copyMeasurements(row, e);
                changes.add(new Change(severityChanged ? Kind.SEVERITY_CHANGED : Kind.REFRESHED, row));
            }
        }
        for (AnomalyEventEntity d : duplicates) {
            d.status = AnomalyEventEntity.CLEARED;
            d.clearedAt = now;
        }
        for (AnomalyEventEntity row : open.values()) {
            if (!byFingerprint.containsKey(row.fingerprint)) {
                row.status = AnomalyEventEntity.CLEARED;
                row.clearedAt = now;
                changes.add(new Change(Kind.CLEARED, row));
            }
        }
        return changes;
    }

    private static void copyMeasurements(AnomalyEventEntity row, AnomalyEvent e) {
        row.severity = e.severity().name();
        row.critical = e.severity() == Severity.CRITICAL;
        row.detectionMethod = e.detectionMethod().name();
        row.ruleKey = e.ruleKey();
        row.windowStart = e.windowStart() != null ? e.windowStart() : row.firstBreachAt;
        row.windowEnd = e.windowEnd() != null ? e.windowEnd() : row.windowStart;
        row.observedValue = e.observedValue();
        row.baselineValue = e.baselineValue();
        row.thresholdValue = e.thresholdValue();
        row.deviationScore = e.deviationScore();
        row.consecutiveBreaches = e.consecutiveBreaches();
        row.sampleCount = e.sampleCount();
        row.baselineSampleCount = e.baselineSampleCount();
        row.description = e.description() == null ? "" : e.description();
        row.details = e.details() == null ? null : new LinkedHashMap<>(e.details());
    }

    @Transactional(readOnly = true)
    public List<AnomalyEventEntity> findByIncident(long incidentId) {
        return repository.findByIncidentIdOrderByDetectedAtDescIdDesc(incidentId);
    }

    /** Rebuilds the detector-level event from a stored row. */
    public static AnomalyEvent toEvent(AnomalyEventEntity r, ServiceLookup.Snapshot services) {
        return new AnomalyEvent(r.fingerprint, AnomalyType.valueOf(r.anomalyType), Severity.valueOf(r.severity),
                services.nameOf(r.subjectServiceId), MetricScope.valueOf(r.scope),
                services.nameOf(r.observedByServiceId), r.endpointMethod, r.endpoint, r.dependencyName,
                DetectionMethod.valueOf(r.detectionMethod), r.ruleKey, r.detectedAt, r.firstBreachAt, r.windowStart,
                r.windowEnd, r.observedValue, r.baselineValue, r.thresholdValue, r.deviationScore,
                r.consecutiveBreaches, r.sampleCount, r.baselineSampleCount, r.description,
                r.details == null ? Map.of() : r.details);
    }

    public static AnomalyEventView toView(AnomalyEventEntity r, ServiceLookup.Snapshot services) {
        return new AnomalyEventView(r.id, r.incidentId, r.status, r.lastSeenAt, r.clearedAt, r.occurrenceCount,
                toEvent(r, services));
    }

    @Transactional(readOnly = true)
    public Page<AnomalyEventView> search(String service, Instant from, Instant to, String status, Pageable pageable,
                                         ServiceLookup.Snapshot services) {
        if (status != null && !AnomalyEventEntity.OPEN.equals(status) && !AnomalyEventEntity.CLEARED.equals(status)) {
            throw new ApiException.BadRequest("BAD_REQUEST", "status must be OPEN or CLEARED");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiException.BadRequest("INVALID_RANGE", "from must be before to");
        }
        Long serviceId = null;
        if (service != null && !service.isBlank()) {
            serviceId = services.idOf(service).orElse(null);
            if (serviceId == null) return Page.empty(pageable);
        }
        final Long sid = serviceId;
        Specification<AnomalyEventEntity> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (sid != null) p.add(cb.equal(root.get("subjectServiceId"), sid));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("detectedAt"), from));
            if (to != null) p.add(cb.lessThan(root.<Instant>get("detectedAt"), to));
            return cb.and(p.toArray(new Predicate[0]));
        };
        return repository.findAll(spec, pageable).map(r -> toView(r, services));
    }
}
