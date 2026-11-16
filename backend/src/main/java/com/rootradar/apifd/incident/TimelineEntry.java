package com.rootradar.apifd.incident;

import com.rootradar.apifd.common.ServiceRef;
import java.time.Instant;
import java.util.Map;

/** One row of incident_timeline_events. Sorted ascending by occurredAt, then id. */
public record TimelineEntry(long id, long incidentId, Instant occurredAt, TimelineEventType eventType,
                            IncidentStatus fromStatus, IncidentStatus toStatus, ServiceRef service, String title,
                            String description, Map<String, Object> details) {}
