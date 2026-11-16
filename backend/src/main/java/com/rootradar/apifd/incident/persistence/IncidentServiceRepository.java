package com.rootradar.apifd.incident.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentServiceRepository extends JpaRepository<IncidentServiceEntity, IncidentServiceId> {
    List<IncidentServiceEntity> findByIncidentIdIn(Collection<Long> incidentIds);
}
