package com.rootradar.apifd.monitoring.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface HealthCheckRepository extends JpaRepository<HealthCheckEntity, Long>,
        JpaSpecificationExecutor<HealthCheckEntity> {

    Optional<HealthCheckEntity> findFirstByServiceIdOrderByCheckedAtDescIdDesc(long serviceId);

    /** Latest row per service. */
    @Query(value = "select distinct on (service_id) * from health_check_results order by service_id, checked_at desc, id desc",
            nativeQuery = true)
    List<HealthCheckEntity> findLatestPerService();
}
