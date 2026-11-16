package com.rootradar.apifd.incident.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactorRepository extends JpaRepository<FactorEntity, Long> {
    List<FactorEntity> findByFindingIdInOrderBySeqAsc(Collection<Long> findingIds);
}
