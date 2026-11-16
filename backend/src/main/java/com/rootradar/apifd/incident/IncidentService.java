package com.rootradar.apifd.incident;

import com.rootradar.apifd.anomaly.AnomalyEventView;
import com.rootradar.apifd.anomaly.DetectionResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Incident lifecycle + queries. Implemented by Agent 5 (bean {@code IncidentServiceImpl}, transactional).
 * Controllers (also Agent 5) and the DetectionPipeline are the callers.
 *
 * <p><b>applyDetection</b> is the single write entry point of the pipeline. Inside one transaction per call it:
 * <ol>
 *   <li>persists/refreshes anomaly_events by fingerprint (new fingerprint -> insert; same fingerprint still firing ->
 *       update last_seen_at/occurrence_count/severity; fingerprint gone -> status CLEARED);</li>
 *   <li>links anomalies to an existing open incident or opens a new one (DEDUP: an anomaly joins an open incident when
 *       its subject node is in that incident's impacted set or graph-connected (upstream/downstream path) to it;
 *       unique index uq_incident_open_dedup is the DB safety net; a RESOLVED incident is reopened if the anomaly
 *       returns within reopen-window-seconds);</li>
 *   <li>advances the state machine using SeriesAssessment (consecutiveNormalWindows, recoveryWindows) with
 *       hysteresis: RECOVERING only when every series linked to the incident is NORMAL, RESOLVED only after
 *       recovery-evaluations consecutive fully-normal evaluations; INSUFFICIENT_BASELINE/NO_DATA never counts as normal;</li>
 *   <li>for every incident that is open (or just changed) builds a DiagnosisInput (graph from DependencyGraphService,
 *       open anomalies, ServiceObservations from assessments + health), calls RootCauseAnalyzer, stores the diagnosis
 *       (+ findings + factors), updates incidents.root_cause_*, appends timeline events;</li>
 *   <li>after commit, publishes SSE events through EventPublisher (never inside the transaction).</li>
 * </ol>
 */
public interface IncidentService {

    /** @return changes made, empty list if nothing changed. Never throws for empty input. */
    List<IncidentChange> applyDetection(DetectionResult detection);

    /** Throws ApiException.NotFound if unknown. */
    IncidentDetail getDetail(long incidentId);

    Optional<IncidentSummary> findSummary(long incidentId);

    Page<IncidentSummary> search(IncidentFilter filter, Pageable pageable);

    /** All non-RESOLVED incidents, most severe then newest first. */
    List<IncidentSummary> findActive();

    List<TimelineEntry> timeline(long incidentId);

    /** Newest first. */
    List<DiagnosisView> diagnoses(long incidentId);

    Optional<DiagnosisView> latestDiagnosis(long incidentId);

    List<AnomalyEventView> anomalies(long incidentId);

    /** Paged anomaly history across incidents; service/from/to/status optional. */
    Page<AnomalyEventView> searchAnomalies(String service, java.time.Instant from, java.time.Instant to,
                                           String status, Pageable pageable);
}
