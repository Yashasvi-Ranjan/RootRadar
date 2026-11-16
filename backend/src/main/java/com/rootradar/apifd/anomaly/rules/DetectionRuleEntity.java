package com.rootradar.apifd.anomaly.rules;

import com.rootradar.apifd.anomaly.RuleMetric;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/** Row of {@code detection_rules}. Owned by the anomaly.rules package. */
@Entity
@Table(name = "detection_rules")
public class DetectionRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_key", nullable = false, length = 100)
    private String ruleKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric", nullable = false, length = 40)
    private RuleMetric metric;

    @Column(name = "service_id")
    private Long serviceId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "warning_threshold")
    private Double warningThreshold;

    @Column(name = "critical_threshold")
    private Double criticalThreshold;

    @Column(name = "baseline_ratio")
    private Double baselineRatio;

    @Column(name = "z_score_threshold")
    private Double zScoreThreshold;

    @Column(name = "min_requests", nullable = false)
    private int minRequests = 3;

    @Column(name = "consecutive_windows", nullable = false)
    private int consecutiveWindows = 3;

    @Column(name = "critical_consecutive_windows", nullable = false)
    private int criticalConsecutiveWindows = 2;

    @Column(name = "baseline_windows", nullable = false)
    private int baselineWindows = 60;

    @Column(name = "min_baseline_windows", nullable = false)
    private int minBaselineWindows = 12;

    @Column(name = "recovery_windows", nullable = false)
    private int recoveryWindows = 3;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }
    public RuleMetric getMetric() { return metric; }
    public void setMetric(RuleMetric metric) { this.metric = metric; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Double getWarningThreshold() { return warningThreshold; }
    public void setWarningThreshold(Double v) { this.warningThreshold = v; }
    public Double getCriticalThreshold() { return criticalThreshold; }
    public void setCriticalThreshold(Double v) { this.criticalThreshold = v; }
    public Double getBaselineRatio() { return baselineRatio; }
    public void setBaselineRatio(Double v) { this.baselineRatio = v; }
    public Double getZScoreThreshold() { return zScoreThreshold; }
    public void setZScoreThreshold(Double v) { this.zScoreThreshold = v; }
    public int getMinRequests() { return minRequests; }
    public void setMinRequests(int v) { this.minRequests = v; }
    public int getConsecutiveWindows() { return consecutiveWindows; }
    public void setConsecutiveWindows(int v) { this.consecutiveWindows = v; }
    public int getCriticalConsecutiveWindows() { return criticalConsecutiveWindows; }
    public void setCriticalConsecutiveWindows(int v) { this.criticalConsecutiveWindows = v; }
    public int getBaselineWindows() { return baselineWindows; }
    public void setBaselineWindows(int v) { this.baselineWindows = v; }
    public int getMinBaselineWindows() { return minBaselineWindows; }
    public void setMinBaselineWindows(int v) { this.minBaselineWindows = v; }
    public int getRecoveryWindows() { return recoveryWindows; }
    public void setRecoveryWindows(int v) { this.recoveryWindows = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
