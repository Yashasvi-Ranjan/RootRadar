package com.rootradar.apifd.telemetry.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PollResultRepository extends JpaRepository<PollResultEntity, Long> {
    Optional<PollResultEntity> findFirstByServiceIdOrderByPolledAtDescIdDesc(long serviceId);
}
