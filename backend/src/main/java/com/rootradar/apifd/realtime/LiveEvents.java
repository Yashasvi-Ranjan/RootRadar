package com.rootradar.apifd.realtime;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.incident.IncidentChangeType;
import com.rootradar.apifd.incident.IncidentSummary;
import com.rootradar.apifd.monitoring.HealthCheckResult;
import java.time.Instant;

/** Payload records for {@link LiveEvent#data()}. Frozen contract, see docs/API_CONTRACTS.md section 6. */
public final class LiveEvents {
    private LiveEvents() {}

    /** health.updated */
    public record HealthUpdated(long serviceId, String serviceName, HealthCheckResult result) {}

    /** telemetry.updated: emitted after a poll stored at least one new window. */
    public record TelemetryUpdated(long serviceId, String serviceName, int windowsStored, Instant latestWindowEnd) {}

    /** anomaly.detected (first time a fingerprint fires or its severity changes) and anomaly.cleared. */
    public record AnomalyChanged(long anomalyId, Long incidentId, AnomalyEvent event) {}

    /** incident.opened | incident.updated | incident.resolved */
    public record IncidentChanged(IncidentChangeType change, IncidentSummary incident) {}

    /** diagnosis.updated */
    public record DiagnosisUpdated(long incidentId, long diagnosisId, String probableCauseService, Integer topScore,
                                   boolean conclusive, Instant analyzedAt) {}

    /** heartbeat */
    public record Heartbeat(Instant serverTime) {}
}
