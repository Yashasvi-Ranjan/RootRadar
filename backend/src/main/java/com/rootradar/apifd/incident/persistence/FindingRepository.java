package com.rootradar.apifd.incident.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<FindingEntity, Long> {
    List<FindingEntity> findByDiagnosisIdInOrderByRankAsc(Collection<Long> diagnosisIds);
}
