package com.rootradar.apifd.incident.persistence;

import com.rootradar.apifd.incident.IncidentStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IncidentRepository extends JpaRepository<IncidentEntity, Long>, JpaSpecificationExecutor<IncidentEntity> {
    List<IncidentEntity> findByStatusNot(IncidentStatus status);

    List<IncidentEntity> findByStatusAndResolvedAtGreaterThanEqualOrderByResolvedAtDesc(IncidentStatus status,
                                                                                         Instant cutoff);
}
