package com.rootradar.apifd.realtime;

/** SSE {@code event:} names. {@link #wireName()} is exactly what the browser registers with addEventListener. */
public enum LiveEventType {
    HEALTH_UPDATED("health.updated"),
    TELEMETRY_UPDATED("telemetry.updated"),
    ANOMALY_DETECTED("anomaly.detected"),
    ANOMALY_CLEARED("anomaly.cleared"),
    INCIDENT_OPENED("incident.opened"),
    INCIDENT_UPDATED("incident.updated"),
    INCIDENT_RESOLVED("incident.resolved"),
    DIAGNOSIS_UPDATED("diagnosis.updated"),
    HEARTBEAT("heartbeat");

    private final String wireName;

    LiveEventType(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }
}
