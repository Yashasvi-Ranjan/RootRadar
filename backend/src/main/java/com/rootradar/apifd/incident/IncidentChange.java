package com.rootradar.apifd.incident;

/** What happened to an incident during one applyDetection call (also drives SSE publication). */
public record IncidentChange(long incidentId, IncidentChangeType type, IncidentStatus fromStatus, IncidentStatus toStatus) {}
