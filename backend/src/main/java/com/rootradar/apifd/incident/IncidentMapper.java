package com.rootradar.apifd.incident;

import com.rootradar.apifd.anomaly.store.ServiceLookup;
import com.rootradar.apifd.common.ServiceRef;
import com.rootradar.apifd.incident.persistence.DiagnosisEntity;
import com.rootradar.apifd.incident.persistence.DiagnosisRepository;
import com.rootradar.apifd.incident.persistence.FactorEntity;
import com.rootradar.apifd.incident.persistence.FactorRepository;
import com.rootradar.apifd.incident.persistence.FindingEntity;
import com.rootradar.apifd.incident.persistence.FindingRepository;
import com.rootradar.apifd.incident.persistence.IncidentEntity;
import com.rootradar.apifd.incident.persistence.IncidentServiceEntity;
import com.rootradar.apifd.incident.persistence.IncidentServiceRepository;
import com.rootradar.apifd.incident.persistence.TimelineEventEntity;
import com.rootradar.apifd.rca.DiagnosisResult;
import com.rootradar.apifd.rca.ScoreFactor;
import com.rootradar.apifd.rca.ServiceFinding;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Entity -> API record conversion for incidents, timeline and diagnoses (batched to avoid N+1 queries). */
@Component
class IncidentMapper {
    private final IncidentServiceRepository incidentServices;
    private final DiagnosisRepository diagnoses;
    private final FindingRepository findings;
    private final FactorRepository factors;
    private final Clock clock;

    IncidentMapper(IncidentServiceRepository incidentServices, DiagnosisRepository diagnoses,
                   FindingRepository findings, FactorRepository factors, Clock clock) {
        this.incidentServices = incidentServices;
        this.diagnoses = diagnoses;
        this.findings = findings;
        this.factors = factors;
        this.clock = clock;
    }

    List<IncidentSummary> summaries(Collection<IncidentEntity> incidents, ServiceLookup.Snapshot services) {
        if (incidents.isEmpty()) return List.of();
        List<Long> ids = incidents.stream().map(i -> i.id).toList();
        Map<Long, List<IncidentServiceEntity>> members = incidentServices.findByIncidentIdIn(ids).stream()
                .collect(Collectors.groupingBy(m -> m.incidentId));
        Instant now = clock.instant();
        List<IncidentSummary> out = new ArrayList<>();
        for (IncidentEntity i : incidents) {
            List<ServiceRef> impacted = members.getOrDefault(i.id, List.of()).stream()
                    .sorted(Comparator.comparing((IncidentServiceEntity m) -> m.firstAbnormalAt,
                            Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(m -> m.serviceId))
                    .map(m -> services.refOf(m.serviceId)).toList();
            Instant end = i.resolvedAt != null ? i.resolvedAt : now;
            long duration = Math.max(0, Duration.between(i.detectedAt, end).getSeconds());
            out.add(new IncidentSummary(i.id, i.title, i.status, i.severity, i.affectedEndpoint,
                    services.refOf(i.rootCauseServiceId), i.rootCauseScore, i.rootCauseSummary, impacted,
                    i.detectedAt, i.activatedAt, i.recoveringAt, i.resolvedAt, i.resolutionReason, i.reopenCount,
                    duration));
        }
        return out;
    }

    IncidentSummary summary(IncidentEntity incident, ServiceLookup.Snapshot services) {
        return summaries(List.of(incident), services).get(0);
    }

    TimelineEntry timeline(TimelineEventEntity t, ServiceLookup.Snapshot services) {
        return new TimelineEntry(t.id, t.incidentId, t.occurredAt, t.eventType, t.fromStatus, t.toStatus,
                services.refOf(t.serviceId), t.title, t.description, t.details == null ? Map.of() : t.details);
    }

    /** Newest first, as the entities were given. */
    List<DiagnosisView> diagnosisViews(List<DiagnosisEntity> rows, ServiceLookup.Snapshot services) {
        if (rows.isEmpty()) return List.of();
        List<Long> diagnosisIds = rows.stream().map(d -> d.id).toList();
        List<FindingEntity> findingRows = findings.findByDiagnosisIdInOrderByRankAsc(diagnosisIds);
        Map<Long, List<FactorEntity>> factorsByFinding = findingRows.isEmpty() ? Map.of()
                : factors.findByFindingIdInOrderBySeqAsc(findingRows.stream().map(f -> f.id).toList()).stream()
                        .collect(Collectors.groupingBy(f -> f.findingId));
        Map<Long, List<FindingEntity>> findingsByDiagnosis = new HashMap<>();
        for (FindingEntity f : findingRows) findingsByDiagnosis.computeIfAbsent(f.diagnosisId, k -> new ArrayList<>()).add(f);
        List<DiagnosisView> out = new ArrayList<>();
        for (DiagnosisEntity d : rows) {
            List<ServiceFinding> fs = new ArrayList<>();
            for (FindingEntity f : findingsByDiagnosis.getOrDefault(d.id, List.of())) {
                List<ScoreFactor> sf = factorsByFinding.getOrDefault(f.id, List.of()).stream()
                        .map(x -> new ScoreFactor(x.ruleKey, x.label, x.points, x.maxPoints, x.fired, x.detail))
                        .toList();
                fs.add(new ServiceFinding(services.nameOf(f.serviceId), f.rank, f.classification, f.score,
                        f.firstAbnormalAt, sf, f.evidence == null ? List.of() : f.evidence));
            }
            DiagnosisResult r = new DiagnosisResult(d.analyzedAt, d.modelVersion,
                    services.nameOf(d.probableCauseServiceId), d.topScore, d.conclusive, d.summary, fs,
                    d.caveats == null ? List.of() : d.caveats);
            out.add(new DiagnosisView(d.id, d.incidentId, r));
        }
        return out;
    }
}
