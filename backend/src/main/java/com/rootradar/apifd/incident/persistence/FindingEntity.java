package com.rootradar.apifd.incident.persistence;

import com.rootradar.apifd.rca.FindingClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code incident_diagnosis_findings}. */
@Entity
@Table(name = "incident_diagnosis_findings")
public class FindingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "diagnosis_id", nullable = false)
    public Long diagnosisId;
    @Column(name = "service_id", nullable = false)
    public Long serviceId;
    @Column(name = "rank", nullable = false)
    public int rank;
    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false)
    public FindingClassification classification;
    @Column(name = "score", nullable = false)
    public int score;
    @Column(name = "first_abnormal_at")
    public Instant firstAbnormalAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    public List<String> evidence;
}
