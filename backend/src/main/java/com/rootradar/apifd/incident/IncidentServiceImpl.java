package com.rootradar.apifd.incident;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.anomaly.AnomalyEventView;
import com.rootradar.apifd.anomaly.DetectionResult;
import com.rootradar.apifd.anomaly.SeriesAssessment;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.anomaly.store.AnomalyStore;
import com.rootradar.apifd.anomaly.store.ServiceLookup;
import com.rootradar.apifd.anomaly.store.persistence.AnomalyEventEntity;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.graph.DependencyGraph;
import com.rootradar.apifd.graph.DependencyGraphService;
import com.rootradar.apifd.graph.GraphNode;
import com.rootradar.apifd.incident.persistence.DiagnosisEntity;
import com.rootradar.apifd.incident.persistence.DiagnosisRepository;
import com.rootradar.apifd.incident.persistence.IncidentEntity;
import com.rootradar.apifd.incident.persistence.IncidentRepository;
import com.rootradar.apifd.incident.persistence.IncidentServiceEntity;
import com.rootradar.apifd.incident.persistence.IncidentServiceRepository;
import com.rootradar.apifd.incident.persistence.TimelineEventEntity;
import com.rootradar.apifd.incident.persistence.TimelineEventRepository;
import com.rootradar.apifd.monitoring.HealthCheckResult;
import com.rootradar.apifd.monitoring.HealthQueryService;
import com.rootradar.apifd.monitoring.HealthStatus;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.rca.DiagnosisInput;
import com.rootradar.apifd.rca.DiagnosisResult;
import com.rootradar.apifd.rca.FindingClassification;
import com.rootradar.apifd.rca.ServiceFinding;
import com.rootradar.apifd.rca.ServiceObservation;
import com.rootradar.apifd.realtime.EventPublisher;
import com.rootradar.apifd.realtime.LiveEvent;
import com.rootradar.apifd.realtime.LiveEventType;
import com.rootradar.apifd.realtime.LiveEvents;
import com.rootradar.apifd.telemetry.MetricScope;
import com.rootradar.apifd.telemetry.MetricsSource;
import com.rootradar.apifd.telemetry.SeriesKey;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Incident lifecycle (see docs/ARCHITECTURE.md section 6) and queries. {@link #applyDetection} is the only write
 * entry. All state-machine timestamps are the detection's {@code evaluatedAt}, never the wall clock, so behaviour is
 * deterministic and testable.
 *
 * <p>Hysteresis: DETECTED confirms after {@code confirm-evaluations} evaluations (or immediately if CRITICAL);
 * ACTIVE -> RECOVERING needs every linked series NORMAL; RECOVERING -> RESOLVED needs
 * {@code recovery-evaluations} consecutive fully-normal evaluations (the one that started RECOVERING counts); any
 * anomaly in between aborts back to ACTIVE. The consecutive-evaluation counter is in memory: after a restart a
 * RECOVERING incident simply needs a fresh run of normal evaluations (the safe direction).
 */
@Service
@Transactional(readOnly = true)
public class IncidentServiceImpl implements IncidentService {
    private static final Logger log = LoggerFactory.getLogger(IncidentServiceImpl.class);
    private static final Set<SeriesState> NO_EVIDENCE = EnumSet.of(SeriesState.NORMAL, SeriesState.NO_DATA,
            SeriesState.INSUFFICIENT_BASELINE);

    private final IncidentRepository incidents;
    private final IncidentServiceRepository members;
    private final TimelineEventRepository timelineRepo;
    private final DiagnosisRepository diagnosisRepo;
    private final AnomalyStore anomalyStore;
    private final ServiceLookup lookup;
    private final DependencyGraphService graphService;
    private final HealthQueryService healthQuery;
    private final IncidentDiagnoser diagnoser;
    private final IncidentMapper mapper;
    private final EventPublisher publisher;
    private final ApifdProperties props;
    /** Consecutive fully-normal evaluations of RECOVERING incidents (see class Javadoc). */
    private final Map<Long, Integer> normalEvaluations = new ConcurrentHashMap<>();

    public IncidentServiceImpl(IncidentRepository incidents, IncidentServiceRepository members,
                               TimelineEventRepository timelineRepo, DiagnosisRepository diagnosisRepo,
                               AnomalyStore anomalyStore, ServiceLookup lookup, DependencyGraphService graphService,
                               HealthQueryService healthQuery, IncidentDiagnoser diagnoser, IncidentMapper mapper,
                               EventPublisher publisher, ApifdProperties props) {
        this.incidents = incidents;
        this.members = members;
        this.timelineRepo = timelineRepo;
        this.diagnosisRepo = diagnosisRepo;
        this.anomalyStore = anomalyStore;
        this.lookup = lookup;
        this.graphService = graphService;
        this.healthQuery = healthQuery;
        this.diagnoser = diagnoser;
        this.mapper = mapper;
        this.publisher = publisher;
        this.props = props;
    }

    // ------------------------------------------------------------------ write side

    /** Working state of one incident during one applyDetection call. */
    private static final class Ctx {
        final IncidentEntity incident;
        final Map<Long, IncidentServiceEntity> memberRows = new LinkedHashMap<>();
        final Set<String> names = new HashSet<>();
        final List<AnomalyEventEntity> firing = new ArrayList<>();
        boolean created;
        boolean changed;

        Ctx(IncidentEntity incident) {
            this.incident = incident;
        }
    }

    private final class Run {
        final Instant now;
        final ServiceLookup.Snapshot services;
        final DependencyGraph graph;
        final Map<SeriesKey, SeriesAssessment> assessments = new HashMap<>();
        final Map<Long, Ctx> open = new LinkedHashMap<>();
        final List<IncidentChange> changes = new ArrayList<>();
        final List<LiveEvent> anomalyEvents = new ArrayList<>();
        final List<DiagnosisUpdatedRef> diagnoses = new ArrayList<>();
        List<IncidentEntity> recentlyResolved;

        Run(Instant now, ServiceLookup.Snapshot services, DependencyGraph graph) {
            this.now = now;
            this.services = services;
            this.graph = graph;
        }

        void change(Ctx c, IncidentChangeType type, IncidentStatus from, IncidentStatus to) {
            c.changed = true;
            changes.add(new IncidentChange(c.incident.id, type, from, to));
        }
    }

    private record DiagnosisUpdatedRef(long incidentId, DiagnosisEntity diagnosis, String probableCause) {}

    @Override
    @Transactional
    public List<IncidentChange> applyDetection(DetectionResult detection) {
        if (detection == null || detection.evaluatedAt() == null) return List.of();
        Instant now = detection.evaluatedAt();
        ServiceLookup.Snapshot services = lookup.snapshot();
        DependencyGraph graph = graphService.currentGraph();
        Run run = new Run(now, services, graph);
        if (detection.assessments() != null) {
            for (SeriesAssessment a : detection.assessments()) run.assessments.put(a.key(), a);
        }

        List<AnomalyStore.Change> anomalyChanges = anomalyStore.sync(
                detection.anomalies() == null ? List.of() : detection.anomalies(), now, services);

        for (IncidentEntity i : incidents.findByStatusNot(IncidentStatus.RESOLVED)) {
            run.open.put(i.id, newCtx(i, services));
        }

        // 1. link firing anomalies to incidents (dedup), oldest breach first so the earliest becomes the opener
        List<AnomalyStore.Change> firing = anomalyChanges.stream()
                .filter(c -> c.kind() != AnomalyStore.Kind.CLEARED)
                .sorted(Comparator.comparing((AnomalyStore.Change c) -> c.entity().firstBreachAt)
                        .thenComparing(c -> c.entity().critical ? 0 : 1).thenComparing(c -> c.entity().id))
                .toList();
        for (AnomalyStore.Change c : firing) {
            Ctx ctx = link(run, c.entity());
            ctx.firing.add(c.entity());
            if (c.kind() == AnomalyStore.Kind.CREATED || c.kind() == AnomalyStore.Kind.SEVERITY_CHANGED) {
                run.anomalyEvents.add(anomalyEvent(LiveEventType.ANOMALY_DETECTED, c.entity(), now, services));
            }
        }
        for (AnomalyStore.Change c : anomalyChanges) {
            if (c.kind() != AnomalyStore.Kind.CLEARED) continue;
            AnomalyEventEntity e = c.entity();
            run.anomalyEvents.add(anomalyEvent(LiveEventType.ANOMALY_CLEARED, e, now, services));
            Ctx ctx = e.incidentId == null ? null : run.open.get(e.incidentId);
            if (ctx != null) {
                timeline(ctx, now, TimelineEventType.ANOMALY_CLEARED, null, null, e.subjectServiceId,
                        describeAnomalyType(e) + " anomaly cleared in " + services.nameOf(e.subjectServiceId),
                        "No longer firing after " + e.occurrenceCount + " evaluation(s).",
                        Map.of("fingerprint", e.fingerprint));
                ctx.changed = true;
            }
        }

        // 2. severity, state machine, endpoint, diagnosis
        for (Ctx ctx : new ArrayList<>(run.open.values())) {
            escalateSeverity(run, ctx);
            advance(run, ctx);
            if (ctx.incident.status != IncidentStatus.RESOLVED) {
                updateAffectedEndpoint(run, ctx);
                diagnose(run, ctx);
            }
            ctx.incident.lastEvaluatedAt = now;
            if (ctx.changed) ctx.incident.updatedAt = now;
        }
        incidents.flush();

        // 3. events (after commit)
        publishAfterCommit(buildEvents(run));
        return List.copyOf(run.changes);
    }

    private Ctx newCtx(IncidentEntity incident, ServiceLookup.Snapshot services) {
        Ctx ctx = new Ctx(incident);
        for (IncidentServiceEntity m : members.findByIncidentIdIn(List.of(incident.id))) {
            ctx.memberRows.put(m.serviceId, m);
            String n = services.nameOf(m.serviceId);
            if (n != null) ctx.names.add(n);
        }
        return ctx;
    }

    // ---- linking / dedup

    private Ctx link(Run run, AnomalyEventEntity a) {
        Ctx linked = a.incidentId == null ? null : run.open.get(a.incidentId);
        if (linked != null) {
            addMember(run, linked, a.subjectServiceId, a.firstBreachAt);
            return linked;
        }
        a.incidentId = null;
        Set<String> subjects = new HashSet<>();
        subjects.add(run.services.nameOf(a.subjectServiceId));
        if (a.observedByServiceId != null) subjects.add(run.services.nameOf(a.observedByServiceId));

        Ctx target = null;
        for (Ctx c : run.open.values()) {
            if (connected(run.graph, c.names, subjects)) {
                target = c;
                break;
            }
        }
        boolean linkedExisting = target != null;
        if (target == null) target = reopenCandidate(run, subjects);
        if (target == null) target = openNew(run, a);

        a.incidentId = target.incident.id;
        addMember(run, target, a.subjectServiceId, a.firstBreachAt);
        timeline(target, run.now, TimelineEventType.ANOMALY_DETECTED, null, null, a.subjectServiceId,
                describeAnomalyType(a) + " anomaly in " + run.services.nameOf(a.subjectServiceId),
                a.description, details("fingerprint", a.fingerprint, "severity", a.severity, "ruleKey", a.ruleKey,
                        "firstBreachAt", a.firstBreachAt.toString(), "observedValue", a.observedValue,
                        "baselineValue", a.baselineValue, "thresholdValue", a.thresholdValue));
        if (linkedExisting && !target.created) {
            run.change(target, IncidentChangeType.ANOMALY_LINKED, target.incident.status, target.incident.status);
        }
        return target;
    }

    private static boolean connected(DependencyGraph graph, Set<String> incidentNames, Set<String> subjects) {
        for (String s : subjects) {
            if (s == null) continue;
            if (incidentNames.contains(s)) return true;
            for (String m : incidentNames) {
                if (graph.hasPath(s, m) || graph.hasPath(m, s)) return true;
            }
        }
        return false;
    }

    private Ctx reopenCandidate(Run run, Set<String> subjects) {
        if (run.recentlyResolved == null) {
            Instant cutoff = run.now.minusSeconds(props.incident().reopenWindowSeconds());
            run.recentlyResolved = new ArrayList<>(incidents
                    .findByStatusAndResolvedAtGreaterThanEqualOrderByResolvedAtDesc(IncidentStatus.RESOLVED, cutoff));
        }
        for (IncidentEntity r : run.recentlyResolved) {
            Ctx c = newCtx(r, run.services);
            if (!connected(run.graph, c.names, subjects)) continue;
            run.recentlyResolved.remove(r);
            IncidentStatus from = r.status;
            long secondsSinceResolved = Duration.between(r.resolvedAt, run.now).toSeconds();
            r.status = IncidentStatus.ACTIVE;
            r.resolvedAt = null;
            r.resolutionReason = null;
            r.recoveringAt = null;
            r.reopenCount++;
            r.lastAnomalyAt = run.now;
            normalEvaluations.remove(r.id);
            run.open.put(r.id, c);
            timeline(c, run.now, TimelineEventType.REOPENED, from, IncidentStatus.ACTIVE, null,
                    "Incident reopened",
                    "The anomaly returned " + secondsSinceResolved
                            + " s after resolution (reopen window " + props.incident().reopenWindowSeconds() + " s).",
                    Map.of("reopenCount", r.reopenCount));
            run.change(c, IncidentChangeType.REOPENED, from, IncidentStatus.ACTIVE);
            return c;
        }
        return null;
    }

    private Ctx openNew(Run run, AnomalyEventEntity opener) {
        IncidentEntity i = new IncidentEntity();
        i.status = IncidentStatus.DETECTED;
        i.severity = Severity.valueOf(opener.severity);
        i.dedupKey = opener.fingerprint;
        i.title = title(opener.fingerprint, null, run.services.nameOf(opener.subjectServiceId));
        i.detectedAt = run.now;
        i.lastAnomalyAt = run.now;
        i.lastEvaluatedAt = run.now;
        i.updatedAt = run.now;
        i = incidents.saveAndFlush(i);
        Ctx c = new Ctx(i);
        c.created = true;
        c.changed = true;
        run.open.put(i.id, c);
        timeline(c, run.now, TimelineEventType.INCIDENT_DETECTED, null, IncidentStatus.DETECTED,
                opener.subjectServiceId, i.title, opener.description,
                details("fingerprint", opener.fingerprint, "firstBreachAt", opener.firstBreachAt.toString(),
                        "severity", opener.severity));
        run.changes.add(new IncidentChange(i.id, IncidentChangeType.OPENED, null, IncidentStatus.DETECTED));
        return c;
    }

    private void addMember(Run run, Ctx c, Long serviceId, Instant firstAbnormalAt) {
        if (serviceId == null) return;
        IncidentServiceEntity row = c.memberRows.get(serviceId);
        if (row == null) {
            row = members.save(new IncidentServiceEntity(c.incident.id, serviceId, firstAbnormalAt));
            c.memberRows.put(serviceId, row);
            String n = run.services.nameOf(serviceId);
            if (n != null) c.names.add(n);
            c.changed = true;
        } else if (firstAbnormalAt != null && (row.firstAbnormalAt == null || firstAbnormalAt.isBefore(row.firstAbnormalAt))) {
            row.firstAbnormalAt = firstAbnormalAt;
        }
    }

    // ---- severity / state machine

    private void escalateSeverity(Run run, Ctx c) {
        Severity max = c.incident.severity;
        for (AnomalyEventEntity a : c.firing) {
            Severity s = Severity.valueOf(a.severity);
            if (s.isAtLeast(max) && s != max) max = s;
        }
        if (max == c.incident.severity) return;
        Severity previous = c.incident.severity;
        c.incident.severity = max;
        if (!c.created) {
            timeline(c, run.now, TimelineEventType.SEVERITY_CHANGED, c.incident.status, c.incident.status, null,
                    "Severity raised to " + max, "Severity escalated from " + previous + " to " + max
                            + "; incidents never auto-downgrade while open.",
                    Map.of("from", previous.name(), "to", max.name()));
            run.change(c, IncidentChangeType.SEVERITY_CHANGED, c.incident.status, c.incident.status);
        }
    }

    private void advance(Run run, Ctx c) {
        IncidentEntity i = c.incident;
        Instant now = run.now;
        ApifdProperties.Incident cfg = props.incident();
        List<AnomalyEventEntity> linked = anomalyStore.findByIncident(i.id);

        Set<SeriesKey> keys = new HashSet<>();
        for (AnomalyEventEntity a : linked) keys.add(seriesKey(a, run.services));
        boolean anyAnomalous = false;
        boolean allNormal = !keys.isEmpty();
        boolean onlyNoEvidence = true;
        for (SeriesKey k : keys) {
            SeriesAssessment a = run.assessments.get(k);
            SeriesState st = a == null ? SeriesState.NO_DATA : a.state();
            if (st == SeriesState.ANOMALOUS) anyAnomalous = true;
            if (st != SeriesState.NORMAL) allNormal = false;
            if (!NO_EVIDENCE.contains(st)) onlyNoEvidence = false;
        }
        boolean active = !c.firing.isEmpty() || anyAnomalous;
        allNormal = allNormal && !active;
        boolean noEvidence = !active && !allNormal && onlyNoEvidence;
        if (!c.firing.isEmpty()) i.lastAnomalyAt = now;

        if (i.status == IncidentStatus.DETECTED) {
            int maxOccurrences = c.firing.stream().mapToInt(a -> a.occurrenceCount).max().orElse(0);
            if (active) {
                if (i.severity == Severity.CRITICAL || maxOccurrences >= cfg.confirmEvaluations()) {
                    i.activatedAt = now;
                    transition(run, c, IncidentStatus.ACTIVE, TimelineEventType.STATUS_CHANGED,
                            "Incident confirmed",
                            i.severity == Severity.CRITICAL
                                    ? "Confirmed immediately: CRITICAL severity."
                                    : "Still anomalous after " + maxOccurrences + " evaluations (confirm-evaluations = "
                                            + cfg.confirmEvaluations() + ").");
                }
                return;
            }
            if (allNormal) {
                resolve(run, c, "RECOVERED", "Anomaly cleared before the incident was confirmed.");
            } else if (noEvidenceTimeout(i, now, cfg, noEvidence)) {
                resolve(run, c, "NO_DATA", noDataText(cfg));
            }
            return;
        }

        boolean justStarted = false;
        if (i.status == IncidentStatus.ACTIVE && !active) {
            if (allNormal) {
                i.recoveringAt = now;
                transition(run, c, IncidentStatus.RECOVERING, TimelineEventType.RECOVERY_STARTED,
                        "Recovery started", "All " + keys.size() + " linked series are NORMAL; waiting for "
                                + cfg.recoveryEvaluations() + " consecutive normal evaluations.");
                normalEvaluations.put(i.id, 1);
                justStarted = true;
            } else if (noEvidenceTimeout(i, now, cfg, noEvidence)) {
                resolve(run, c, "NO_DATA", noDataText(cfg));
                return;
            }
        }
        if (i.status == IncidentStatus.RECOVERING) {
            if (active) {
                i.recoveringAt = null;
                normalEvaluations.remove(i.id);
                transition(run, c, IncidentStatus.ACTIVE, TimelineEventType.RECOVERY_ABORTED, "Recovery aborted",
                        "Degradation returned while recovering; incident is ACTIVE again (not a new incident).");
                return;
            }
            if (!justStarted) {
                if (allNormal) normalEvaluations.merge(i.id, 1, Integer::sum);
                else normalEvaluations.put(i.id, 0);
            }
            int streak = normalEvaluations.getOrDefault(i.id, 0);
            if (allNormal && streak >= cfg.recoveryEvaluations()) {
                resolve(run, c, "RECOVERED", streak + " consecutive normal evaluations (recovery-evaluations = "
                        + cfg.recoveryEvaluations() + ").");
            } else if (!allNormal && noEvidenceTimeout(i, now, cfg, noEvidence)) {
                resolve(run, c, "NO_DATA", noDataText(cfg));
            }
        }
    }

    private static boolean noEvidenceTimeout(IncidentEntity i, Instant now, ApifdProperties.Incident cfg,
                                             boolean noEvidence) {
        if (!noEvidence) return false;
        Instant since = i.lastAnomalyAt != null ? i.lastAnomalyAt : i.detectedAt;
        if (i.recoveringAt != null && i.recoveringAt.isAfter(since)) since = i.recoveringAt;
        return !now.isBefore(since.plus(Duration.ofMinutes(cfg.noDataResolveMinutes())));
    }

    private static String noDataText(ApifdProperties.Incident cfg) {
        return "No anomaly firing and no valid samples for " + cfg.noDataResolveMinutes()
                + " minutes (no-data-resolve-minutes); resolved without a confirmed recovery.";
    }

    private void transition(Run run, Ctx c, IncidentStatus to, TimelineEventType type, String title, String desc) {
        IncidentStatus from = c.incident.status;
        c.incident.status = to;
        timeline(c, run.now, type, from, to, null, title, desc, Map.of());
        run.change(c, IncidentChangeType.STATUS_CHANGED, from, to);
    }

    private void resolve(Run run, Ctx c, String reason, String desc) {
        IncidentStatus from = c.incident.status;
        c.incident.status = IncidentStatus.RESOLVED;
        c.incident.resolvedAt = run.now;
        c.incident.resolutionReason = reason;
        normalEvaluations.remove(c.incident.id);
        timeline(c, run.now, TimelineEventType.RESOLVED, from, IncidentStatus.RESOLVED, null,
                "Incident resolved (" + reason + ")", desc, Map.of("resolutionReason", reason));
        run.change(c, IncidentChangeType.RESOLVED, from, IncidentStatus.RESOLVED);
    }

    private static SeriesKey seriesKey(AnomalyEventEntity a, ServiceLookup.Snapshot services) {
        return new SeriesKey(services.nameOf(a.subjectServiceId), MetricScope.valueOf(a.scope),
                blankToNull(a.endpointMethod), blankToNull(a.endpoint), blankToNull(a.dependencyName),
                blankToNull(services.nameOf(a.observedByServiceId)));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ---- affected endpoint, diagnosis

    private void updateAffectedEndpoint(Run run, Ctx c) {
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (AnomalyEventEntity a : c.firing) {
            if (!MetricScope.ENDPOINT.name().equals(a.scope) || a.endpoint == null) continue;
            String subject = run.services.nameOf(a.subjectServiceId);
            boolean entryPoint = run.graph.contains(subject) && run.graph.directUpstream(subject).isEmpty();
            int rank = (entryPoint ? 0 : 2) + (a.critical ? 0 : 1);
            if (rank < bestRank) {
                bestRank = rank;
                best = (a.endpointMethod == null ? "" : a.endpointMethod + " ") + a.endpoint;
            }
        }
        if (best != null && !best.equals(c.incident.affectedEndpoint)) {
            c.incident.affectedEndpoint = best;
            c.changed = true;
        }
    }

    private void diagnose(Run run, Ctx c) {
        IncidentEntity i = c.incident;
        List<AnomalyEventEntity> open = anomalyStore.findByIncident(i.id).stream().filter(AnomalyEventEntity::isOpen)
                .toList();
        if (open.isEmpty()) return; // keep the last diagnosis; nothing new to analyse
        List<AnomalyEvent> events = open.stream().map(a -> AnomalyStore.toEvent(a, run.services)).toList();
        DiagnosisInput input = new DiagnosisInput(run.now, run.graph, events, observations(run, events));
        String signature = IncidentDiagnoser.signature(input);
        Optional<DiagnosisEntity> previous = diagnoser.latest(i.id);
        if (previous.isPresent() && previous.get().inputSnapshot != null
                && signature.equals(previous.get().inputSnapshot.get(IncidentDiagnoser.SIGNATURE_KEY))) {
            return; // evidence unchanged
        }
        Optional<IncidentDiagnoser.Outcome> outcome = diagnoser.diagnose(i.id, input, run.services);
        if (outcome.isEmpty()) return;
        DiagnosisEntity d = outcome.get().entity();
        DiagnosisResult r = outcome.get().result();

        Long previousCause = previous.map(p -> p.probableCauseServiceId).orElse(null);
        boolean first = previous.isEmpty();
        boolean causeChanged = !first && !java.util.Objects.equals(previousCause, d.probableCauseServiceId);
        i.rootCauseServiceId = d.probableCauseServiceId;
        i.rootCauseScore = d.probableCauseServiceId == null ? null : d.topScore;
        i.rootCauseSummary = d.summary;
        i.title = title(i.dedupKey, r.probableCauseService(), null);
        for (ServiceFinding f : r.findings() == null ? List.<ServiceFinding>of() : r.findings()) {
            if (f.classification() == FindingClassification.INSUFFICIENT_EVIDENCE) continue;
            run.services.idOf(f.serviceName()).ifPresent(id -> addMember(run, c, id, f.firstAbnormalAt()));
        }
        if (first || causeChanged) {
            timeline(c, run.now, TimelineEventType.DIAGNOSIS_UPDATED, i.status, i.status, d.probableCauseServiceId,
                    r.probableCauseService() == null ? "Diagnosis: no conclusive cause"
                            : "Diagnosis: probable cause " + r.probableCauseService(),
                    d.summary, details("diagnosisId", d.id, "modelVersion", d.modelVersion, "topScore", d.topScore,
                            "conclusive", d.conclusive));
        }
        run.change(c, IncidentChangeType.DIAGNOSIS_UPDATED, i.status, i.status);
        run.diagnoses.add(new DiagnosisUpdatedRef(i.id, d, r.probableCauseService()));
    }

    private List<ServiceObservation> observations(Run run, List<AnomalyEvent> events) {
        Map<Long, HealthCheckResult> health = Map.of();
        try {
            health = healthQuery.latestForAll();
        } catch (RuntimeException ex) {
            log.warn("Could not load latest health for diagnosis: {}", ex.toString());
        }
        Map<String, Instant> firstAbnormal = new HashMap<>();
        for (AnomalyEvent e : events) {
            if (e.firstBreachAt() != null) firstAbnormal.merge(e.subjectService(), e.firstBreachAt(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }
        List<ServiceObservation> out = new ArrayList<>();
        for (GraphNode n : run.graph.nodes()) {
            // Only nodes connected to this incident's anomalies take part: an unrelated service that happens to be
            // DOWN (a different incident) must not be scored as a candidate cause of this one.
            if (!firstAbnormal.isEmpty() && !firstAbnormal.containsKey(n.name())
                    && firstAbnormal.keySet().stream().noneMatch(s -> run.graph.contains(s)
                    && (run.graph.hasPath(s, n.name()) || run.graph.hasPath(n.name(), s)))) {
                continue;
            }
            SeriesAssessment a = primaryAssessment(run, n);
            HealthStatus hs = HealthStatus.UNKNOWN;
            if (n.nodeType() == NodeType.HTTP_SERVICE) {
                Long id = run.services.idOf(n.name()).orElse(null);
                HealthCheckResult h = id == null ? null : health.get(id);
                if (h != null) hs = h.status();
            }
            MetricsSource source = a == null ? MetricsSource.NONE
                    : n.nodeType() == NodeType.DEPENDENCY_ONLY ? MetricsSource.CALLER_MEASUREMENT
                    : MetricsSource.INBOUND_TELEMETRY;
            out.add(new ServiceObservation(n.name(), n.nodeType(), hs, source,
                    a == null ? SeriesState.NO_DATA : a.state(),
                    a == null ? null : a.currentLatencyP95Ms(), a == null ? null : a.baselineLatencyP95Ms(),
                    a == null ? null : a.currentErrorRate(), a == null ? null : a.baselineErrorRate(),
                    a == null ? 0 : a.recentRequestCount(), firstAbnormal.get(n.name())));
        }
        return out;
    }

    private static SeriesAssessment primaryAssessment(Run run, GraphNode n) {
        if (n.nodeType() == NodeType.HTTP_SERVICE) return run.assessments.get(SeriesKey.inbound(n.name()));
        return run.assessments.values().stream()
                .filter(a -> a.key().scope() == MetricScope.DEPENDENCY_CALL && a.key().serviceName().equals(n.name()))
                .max(Comparator.comparingLong(SeriesAssessment::recentRequestCount)).orElse(null);
    }

    // ---- helpers

    private void timeline(Ctx c, Instant at, TimelineEventType type, IncidentStatus from, IncidentStatus to,
                          Long serviceId, String title, String description, Map<String, Object> details) {
        TimelineEventEntity t = new TimelineEventEntity();
        t.incidentId = c.incident.id;
        t.occurredAt = at;
        t.eventType = type;
        t.fromStatus = from;
        t.toStatus = to;
        t.serviceId = serviceId;
        t.title = title.length() > 300 ? title.substring(0, 300) : title;
        t.description = description;
        t.details = details;
        timelineRepo.save(t);
        c.changed = true;
    }

    private static Map<String, Object> details(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String describeAnomalyType(AnomalyEventEntity a) {
        return switch (a.anomalyType) {
            case "LATENCY" -> "Latency";
            case "ERROR_RATE" -> "Error-rate";
            default -> "Health-check";
        };
    }

    /** Title from the opening fingerprint's type and either the suspected cause or the opening subject. */
    static String title(String dedupKey, String suspectedCause, String openingSubject) {
        String[] parts = dedupKey.split("\\|", -1);
        String phrase = switch (parts[0]) {
            case "LATENCY" -> "Elevated latency";
            case "ERROR_RATE" -> "Elevated error rate";
            default -> "Health check failures";
        };
        String subject = suspectedCause != null ? suspectedCause
                : openingSubject != null ? openingSubject : (parts.length > 2 ? parts[2] : "unknown service");
        return phrase + " in " + subject;
    }

    // ---- events

    private LiveEvent anomalyEvent(LiveEventType type, AnomalyEventEntity e, Instant now, ServiceLookup.Snapshot s) {
        return new LiveEvent(type, now, new LiveEvents.AnomalyChanged(e.id, e.incidentId,
                AnomalyStore.toEvent(e, s)));
    }

    private List<LiveEvent> buildEvents(Run run) {
        List<LiveEvent> events = new ArrayList<>(run.anomalyEvents);
        Set<Long> ids = new HashSet<>();
        run.changes.forEach(c -> ids.add(c.incidentId()));
        if (ids.isEmpty()) return events;
        List<IncidentEntity> entities = new ArrayList<>();
        for (Long id : ids) {
            Ctx c = run.open.get(id);
            if (c != null) entities.add(c.incident);
            else incidents.findById(id).ifPresent(entities::add);
        }
        Map<Long, IncidentSummary> summaries = new HashMap<>();
        for (IncidentSummary s : mapper.summaries(entities, run.services)) summaries.put(s.id(), s);

        Set<Long> terminal = new HashSet<>();
        for (IncidentChange c : run.changes) {
            if (c.type() == IncidentChangeType.OPENED || c.type() == IncidentChangeType.REOPENED
                    || c.type() == IncidentChangeType.RESOLVED) terminal.add(c.incidentId());
        }
        Set<String> seen = new HashSet<>();
        for (IncidentChange c : run.changes) {
            IncidentSummary s = summaries.get(c.incidentId());
            if (s == null || !seen.add(c.incidentId() + ":" + c.type())) continue;
            boolean lifecycle = c.type() == IncidentChangeType.OPENED || c.type() == IncidentChangeType.REOPENED
                    || c.type() == IncidentChangeType.RESOLVED;
            if (!lifecycle && terminal.contains(c.incidentId())) continue;
            LiveEventType type = switch (c.type()) {
                case OPENED, REOPENED -> LiveEventType.INCIDENT_OPENED;
                case RESOLVED -> LiveEventType.INCIDENT_RESOLVED;
                default -> LiveEventType.INCIDENT_UPDATED;
            };
            events.add(new LiveEvent(type, run.now, new LiveEvents.IncidentChanged(c.type(), s)));
        }
        for (DiagnosisUpdatedRef d : run.diagnoses) {
            events.add(new LiveEvent(LiveEventType.DIAGNOSIS_UPDATED, run.now, new LiveEvents.DiagnosisUpdated(
                    d.incidentId(), d.diagnosis().id, d.probableCause(), d.diagnosis().topScore,
                    d.diagnosis().conclusive, d.diagnosis().analyzedAt)));
        }
        return events;
    }

    private void publishAfterCommit(List<LiveEvent> events) {
        if (events.isEmpty()) return;
        Runnable send = () -> {
            for (LiveEvent e : events) {
                try {
                    publisher.publish(e);
                } catch (RuntimeException ex) {
                    log.warn("Publishing {} failed: {}", e.type(), ex.toString());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    // ------------------------------------------------------------------ read side

    @Override
    public IncidentDetail getDetail(long incidentId) {
        IncidentEntity i = require(incidentId);
        ServiceLookup.Snapshot s = lookup.snapshot();
        List<AnomalyEventView> anomalies = anomalyStore.findByIncident(incidentId).stream().limit(200)
                .map(a -> AnomalyStore.toView(a, s)).toList();
        DiagnosisView latest = latestDiagnosis(incidentId).orElse(null);
        return new IncidentDetail(mapper.summary(i, s), latest, anomalies, timeline(incidentId));
    }

    @Override
    public Optional<IncidentSummary> findSummary(long incidentId) {
        return incidents.findById(incidentId).map(i -> mapper.summary(i, lookup.snapshot()));
    }

    @Override
    public Page<IncidentSummary> search(IncidentFilter f, Pageable pageable) {
        if (f.from() != null && f.to() != null) {
            if (!f.from().isBefore(f.to())) throw new ApiException.BadRequest("INVALID_RANGE", "from must be before to");
            if (Duration.between(f.from(), f.to()).compareTo(com.rootradar.apifd.common.Constants.MAX_QUERY_RANGE) > 0) {
                throw new ApiException.BadRequest("INVALID_RANGE", "range must not exceed 31 days");
            }
        }
        ServiceLookup.Snapshot s = lookup.snapshot();
        Long serviceId = null;
        if (f.service() != null && !f.service().isBlank()) {
            serviceId = s.idOf(f.service()).orElse(null);
            if (serviceId == null) return Page.empty(pageable);
        }
        final Long sid = serviceId;
        Specification<IncidentEntity> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.statuses() != null && !f.statuses().isEmpty()) p.add(root.get("status").in(f.statuses()));
            if (f.severity() != null) p.add(cb.equal(root.get("severity"), f.severity()));
            if (f.from() != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("detectedAt"), f.from()));
            if (f.to() != null) p.add(cb.lessThan(root.<Instant>get("detectedAt"), f.to()));
            if (sid != null) {
                Subquery<Long> sq = q.subquery(Long.class);
                Root<IncidentServiceEntity> m = sq.from(IncidentServiceEntity.class);
                sq.select(m.<Long>get("incidentId")).where(cb.equal(m.get("incidentId"), root.get("id")),
                        cb.equal(m.get("serviceId"), sid));
                p.add(cb.or(cb.equal(root.get("rootCauseServiceId"), sid), cb.exists(sq)));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<IncidentEntity> page = incidents.findAll(spec, pageable);
        return new PageImpl<>(mapper.summaries(page.getContent(), s), pageable, page.getTotalElements());
    }

    @Override
    public List<IncidentSummary> findActive() {
        ServiceLookup.Snapshot s = lookup.snapshot();
        return mapper.summaries(incidents.findByStatusNot(IncidentStatus.RESOLVED), s).stream()
                .sorted(Comparator.comparing((IncidentSummary x) -> x.severity() == Severity.CRITICAL ? 0 : 1)
                        .thenComparing(IncidentSummary::detectedAt, Comparator.reverseOrder())
                        .thenComparing(IncidentSummary::id, Comparator.reverseOrder()))
                .toList();
    }

    @Override
    public List<TimelineEntry> timeline(long incidentId) {
        require(incidentId);
        ServiceLookup.Snapshot s = lookup.snapshot();
        return timelineRepo.findByIncidentIdOrderByOccurredAtAscIdAsc(incidentId).stream()
                .map(t -> mapper.timeline(t, s)).toList();
    }

    @Override
    public List<DiagnosisView> diagnoses(long incidentId) {
        require(incidentId);
        return mapper.diagnosisViews(diagnosisRepo.findByIncidentIdOrderByAnalyzedAtDescIdDesc(incidentId),
                lookup.snapshot());
    }

    @Override
    public Optional<DiagnosisView> latestDiagnosis(long incidentId) {
        require(incidentId);
        return diagnosisRepo.findFirstByIncidentIdOrderByAnalyzedAtDescIdDesc(incidentId)
                .map(d -> mapper.diagnosisViews(List.of(d), lookup.snapshot()).get(0));
    }

    @Override
    public List<AnomalyEventView> anomalies(long incidentId) {
        require(incidentId);
        ServiceLookup.Snapshot s = lookup.snapshot();
        return anomalyStore.findByIncident(incidentId).stream().map(a -> AnomalyStore.toView(a, s)).toList();
    }

    @Override
    public Page<AnomalyEventView> searchAnomalies(String service, Instant from, Instant to, String status,
                                                  Pageable pageable) {
        return anomalyStore.search(service, from, to, status, pageable, lookup.snapshot());
    }

    private IncidentEntity require(long id) {
        return incidents.findById(id).orElseThrow(() -> new ApiException.NotFound("Incident " + id + " not found"));
    }
}
