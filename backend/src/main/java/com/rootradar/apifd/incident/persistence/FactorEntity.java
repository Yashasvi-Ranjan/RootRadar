package com.rootradar.apifd.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Row of {@code incident_diagnosis_factors}: one line of a finding's score breakdown. */
@Entity
@Table(name = "incident_diagnosis_factors")
public class FactorEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "finding_id", nullable = false)
    public Long findingId;
    @Column(name = "seq", nullable = false)
    public int seq;
    @Column(name = "rule_key", nullable = false, length = 60)
    public String ruleKey;
    @Column(name = "label", nullable = false, length = 200)
    public String label;
    @Column(name = "points", nullable = false)
    public int points;
    @Column(name = "max_points", nullable = false)
    public int maxPoints;
    @Column(name = "fired", nullable = false)
    public boolean fired;
    @Column(name = "detail", columnDefinition = "text")
    public String detail;
}
