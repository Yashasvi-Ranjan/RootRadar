package com.rootradar.apifd.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/** Row of {@code incident_services}: membership of the impacted set. */
@Entity
@Table(name = "incident_services")
@IdClass(IncidentServiceId.class)
public class IncidentServiceEntity {
    @Id
    @Column(name = "incident_id")
    public Long incidentId;
    @Id
    @Column(name = "service_id")
    public Long serviceId;
    @Column(name = "first_abnormal_at")
    public Instant firstAbnormalAt;

    public IncidentServiceEntity() {}

    public IncidentServiceEntity(Long incidentId, Long serviceId, Instant firstAbnormalAt) {
        this.incidentId = incidentId;
        this.serviceId = serviceId;
        this.firstAbnormalAt = firstAbnormalAt;
    }
}
