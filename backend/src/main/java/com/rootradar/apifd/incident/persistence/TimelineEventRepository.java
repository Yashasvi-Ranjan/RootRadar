package com.rootradar.apifd.incident.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineEventRepository extends JpaRepository<TimelineEventEntity, Long> {
    List<TimelineEventEntity> findByIncidentIdOrderByOccurredAtAscIdAsc(Long incidentId);
}
