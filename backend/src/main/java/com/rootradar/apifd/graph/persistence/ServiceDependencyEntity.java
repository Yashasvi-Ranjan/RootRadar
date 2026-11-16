package com.rootradar.apifd.graph.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Row of {@code service_dependencies}: fromService CALLS toService. */
@Entity
@Table(name = "service_dependencies")
public class ServiceDependencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "from_service_id", nullable = false)
    private long fromServiceId;
    @Column(name = "to_service_id", nullable = false)
    private long toServiceId;
    @Column(name = "measurement_name", nullable = false, length = 100)
    private String measurementName;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ServiceDependencyEntity() {}

    public ServiceDependencyEntity(long fromServiceId, long toServiceId, String measurementName, Instant createdAt) {
        this.fromServiceId = fromServiceId;
        this.toServiceId = toServiceId;
        this.measurementName = measurementName;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public long getFromServiceId() { return fromServiceId; }
    public long getToServiceId() { return toServiceId; }
    public String getMeasurementName() { return measurementName; }
    public Instant getCreatedAt() { return createdAt; }
}
