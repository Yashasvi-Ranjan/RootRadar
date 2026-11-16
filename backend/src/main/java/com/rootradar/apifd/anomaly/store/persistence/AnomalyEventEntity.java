package com.rootradar.apifd.anomaly.store.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code anomaly_events}: one row per fingerprint episode (refreshed while the same anomaly keeps firing).
 * Persistence-internal state holder: fields are public so the store/incident code can fill it without boilerplate.
 */
@Entity
@Table(name = "anomaly_events")
public class AnomalyEventEntity {
    public static final String OPEN = "OPEN";
    public static final String CLEARED = "CLEARED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "incident_id")
    public Long incidentId;
    @Column(name = "fingerprint", nullable = false, length = 300)
    public String fingerprint;
    @Column(name = "anomaly_type", nullable = false)
    public String anomalyType;
    @Column(name = "severity", nullable = false)
    public String severity;
    @Column(name = "status", nullable = false)
    public String status;
    @Column(name = "subject_service_id", nullable = false)
    public Long subjectServiceId;
    @Column(name = "observed_by_service_id")
    public Long observedByServiceId;
    @Column(name = "scope", nullable = false)
    public String scope;
    @Column(name = "endpoint_method")
    public String endpointMethod;
    @Column(name = "endpoint")
    public String endpoint;
    @Column(name = "dependency_name")
    public String dependencyName;
    @Column(name = "detection_method", nullable = false)
    public String detectionMethod;
    @Column(name = "rule_key", nullable = false)
    public String ruleKey;
    @Column(name = "detected_at", nullable = false)
    public Instant detectedAt;
    @Column(name = "first_breach_at", nullable = false)
    public Instant firstBreachAt;
    @Column(name = "window_start", nullable = false)
    public Instant windowStart;
    @Column(name = "window_end", nullable = false)
    public Instant windowEnd;
    @Column(name = "observed_value", nullable = false)
    public double observedValue;
    @Column(name = "baseline_value")
    public Double baselineValue;
    @Column(name = "threshold_value")
    public Double thresholdValue;
    @Column(name = "deviation_score")
    public Double deviationScore;
    @Column(name = "consecutive_breaches", nullable = false)
    public int consecutiveBreaches;
    @Column(name = "sample_count", nullable = false)
    public int sampleCount;
    @Column(name = "baseline_sample_count", nullable = false)
    public int baselineSampleCount;
    @Column(name = "critical", nullable = false)
    public boolean critical;
    @Column(name = "description", nullable = false, columnDefinition = "text")
    public String description;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    public Map<String, Object> details;
    @Column(name = "last_seen_at", nullable = false)
    public Instant lastSeenAt;
    @Column(name = "cleared_at")
    public Instant clearedAt;
    @Column(name = "occurrence_count", nullable = false)
    public int occurrenceCount = 1;

    public boolean isOpen() {
        return OPEN.equals(status);
    }
}
