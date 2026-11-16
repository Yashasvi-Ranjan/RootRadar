package com.rootradar.apifd.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code incident_diagnoses}: one RCA run. */
@Entity
@Table(name = "incident_diagnoses")
public class DiagnosisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "incident_id", nullable = false)
    public Long incidentId;
    @Column(name = "analyzed_at", nullable = false)
    public Instant analyzedAt;
    @Column(name = "model_version", nullable = false, length = 30)
    public String modelVersion;
    @Column(name = "probable_cause_service_id")
    public Long probableCauseServiceId;
    @Column(name = "top_score")
    public Integer topScore;
    @Column(name = "conclusive", nullable = false)
    public boolean conclusive;
    @Column(name = "summary", nullable = false, columnDefinition = "text")
    public String summary;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "caveats", columnDefinition = "jsonb")
    public List<String> caveats;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", columnDefinition = "jsonb")
    public Map<String, Object> inputSnapshot;
}
