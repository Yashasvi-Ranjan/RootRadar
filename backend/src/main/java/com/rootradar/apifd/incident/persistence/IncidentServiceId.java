package com.rootradar.apifd.incident.persistence;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of incident_services. */
public class IncidentServiceId implements Serializable {
    public Long incidentId;
    public Long serviceId;

    public IncidentServiceId() {}

    public IncidentServiceId(Long incidentId, Long serviceId) {
        this.incidentId = incidentId;
        this.serviceId = serviceId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IncidentServiceId k && Objects.equals(incidentId, k.incidentId)
                && Objects.equals(serviceId, k.serviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(incidentId, serviceId);
    }
}
