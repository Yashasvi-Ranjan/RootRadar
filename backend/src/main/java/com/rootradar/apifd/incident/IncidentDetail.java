package com.rootradar.apifd.incident;

import com.rootradar.apifd.anomaly.AnomalyEventView;
import java.util.List;

/**
 * GET /api/incidents/{id}. latestDiagnosis is null until the first RCA run has completed.
 * anomalies = all anomaly events linked to the incident (newest first, max 200); timeline ascending.
 */
public record IncidentDetail(IncidentSummary incident, DiagnosisView latestDiagnosis,
                             List<AnomalyEventView> anomalies, List<TimelineEntry> timeline) {}
