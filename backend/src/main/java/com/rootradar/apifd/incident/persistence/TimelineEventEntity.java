package com.rootradar.apifd.incident.persistence;

import com.rootradar.apifd.incident.IncidentStatus;
import com.rootradar.apifd.incident.TimelineEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code incident_timeline_events} (append-only audit trail). */
@Entity
@Table(name = "incident_timeline_events")
public class TimelineEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "incident_id", nullable = false)
    public Long incidentId;
    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    public TimelineEventType eventType;
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    public IncidentStatus fromStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    public IncidentStatus toStatus;
    @Column(name = "service_id")
    public Long serviceId;
    @Column(name = "title", nullable = false, length = 300)
    public String title;
    @Column(name = "description", columnDefinition = "text")
    public String description;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    public Map<String, Object> details;
}
