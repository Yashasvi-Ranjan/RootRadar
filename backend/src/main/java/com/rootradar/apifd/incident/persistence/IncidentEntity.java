package com.rootradar.apifd.incident.persistence;

import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.incident.IncidentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Row of {@code incidents}. Persistence-internal state holder (public fields on purpose, no business logic). */
@Entity
@Table(name = "incidents")
public class IncidentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "title", nullable = false, length = 300)
    public String title;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public IncidentStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    public Severity severity;
    @Column(name = "dedup_key", nullable = false, length = 300)
    public String dedupKey;
    @Column(name = "affected_endpoint", length = 300)
    public String affectedEndpoint;
    @Column(name = "root_cause_service_id")
    public Long rootCauseServiceId;
    @Column(name = "root_cause_score")
    public Integer rootCauseScore;
    @Column(name = "root_cause_summary", columnDefinition = "text")
    public String rootCauseSummary;
    @Column(name = "detected_at", nullable = false)
    public Instant detectedAt;
    @Column(name = "activated_at")
    public Instant activatedAt;
    @Column(name = "recovering_at")
    public Instant recoveringAt;
    @Column(name = "resolved_at")
    public Instant resolvedAt;
    @Column(name = "resolution_reason", length = 30)
    public String resolutionReason;
    @Column(name = "last_anomaly_at")
    public Instant lastAnomalyAt;
    @Column(name = "last_evaluated_at")
    public Instant lastEvaluatedAt;
    @Column(name = "reopen_count", nullable = false)
    public int reopenCount;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
    @Version
    @Column(name = "version", nullable = false)
    public Long version;
}
