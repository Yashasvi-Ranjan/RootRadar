package com.rootradar.apifd.incident.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisRepository extends JpaRepository<DiagnosisEntity, Long> {
    List<DiagnosisEntity> findByIncidentIdOrderByAnalyzedAtDescIdDesc(Long incidentId);

    Optional<DiagnosisEntity> findFirstByIncidentIdOrderByAnalyzedAtDescIdDesc(Long incidentId);
}
