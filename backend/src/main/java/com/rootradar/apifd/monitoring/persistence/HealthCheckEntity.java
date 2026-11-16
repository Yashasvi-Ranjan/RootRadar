package com.rootradar.apifd.monitoring.persistence;

import com.rootradar.apifd.monitoring.HealthOutcome;
import com.rootradar.apifd.monitoring.HealthStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Row of {@code health_check_results}. */
@Entity
@Table(name = "health_check_results")
public class HealthCheckEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "service_id", nullable = false)
    private long serviceId;
    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private HealthStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HealthOutcome outcome;
    @Column(name = "http_status")
    private Integer httpStatus;
    @Column(name = "response_time_ms")
    private Integer responseTimeMs;
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    protected HealthCheckEntity() {}

    public HealthCheckEntity(long serviceId, Instant checkedAt, HealthStatus status, HealthOutcome outcome,
                             Integer httpStatus, Integer responseTimeMs, String errorMessage) {
        this.serviceId = serviceId;
        this.checkedAt = checkedAt;
        this.status = status;
        this.outcome = outcome;
        this.httpStatus = httpStatus;
        this.responseTimeMs = responseTimeMs;
        this.errorMessage = errorMessage == null || errorMessage.length() <= 500
                ? errorMessage : errorMessage.substring(0, 500);
    }

    public Long getId() { return id; }
    public long getServiceId() { return serviceId; }
    public Instant getCheckedAt() { return checkedAt; }
    public HealthStatus getStatus() { return status; }
    public HealthOutcome getOutcome() { return outcome; }
    public Integer getHttpStatus() { return httpStatus; }
    public Integer getResponseTimeMs() { return responseTimeMs; }
    public String getErrorMessage() { return errorMessage; }
}
