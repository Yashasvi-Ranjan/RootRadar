package com.rootradar.apifd.anomaly.rules;

import com.rootradar.apifd.anomaly.RuleMetric;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DetectionRuleRepository extends JpaRepository<DetectionRuleEntity, Long> {

    Optional<DetectionRuleEntity> findByRuleKey(String ruleKey);

    Optional<DetectionRuleEntity> findByMetricAndServiceId(RuleMetric metric, Long serviceId);

    Optional<DetectionRuleEntity> findByMetricAndServiceIdIsNull(RuleMetric metric);

    List<DetectionRuleEntity> findAllByOrderByIdAsc();

    List<DetectionRuleEntity> findByServiceIdIsNullOrServiceIdOrderByIdAsc(Long serviceId);
}
