package com.rootradar.apifd.anomaly.store.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AnomalyEventRepository
        extends JpaRepository<AnomalyEventEntity, Long>, JpaSpecificationExecutor<AnomalyEventEntity> {
    List<AnomalyEventEntity> findByStatus(String status);

    List<AnomalyEventEntity> findByIncidentIdOrderByDetectedAtDescIdDesc(Long incidentId);

    List<AnomalyEventEntity> findByIncidentIdIn(Collection<Long> incidentIds);
}
