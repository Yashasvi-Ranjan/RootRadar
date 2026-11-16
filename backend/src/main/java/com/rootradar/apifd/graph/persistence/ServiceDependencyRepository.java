package com.rootradar.apifd.graph.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceDependencyRepository extends JpaRepository<ServiceDependencyEntity, Long> {
    boolean existsByFromServiceIdAndMeasurementName(long fromServiceId, String measurementName);
}
