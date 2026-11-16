package com.rootradar.apifd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootradar.apifd.anomaly.rules.DetectionRuleRepository;
import com.rootradar.apifd.anomaly.store.persistence.AnomalyEventRepository;
import com.rootradar.apifd.incident.DetectionPipeline;
import com.rootradar.apifd.incident.persistence.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the REAL {@link Application} (no test-support scan restrictions) against apifd_test to prove all modules wire
 * together: every repository is visible and the detection pipeline bean exists. Background schedulers are disabled.
 */
@SpringBootTest(classes = Application.class, properties = {
        "apifd.monitoring.scheduling-enabled=false", "apifd.telemetry.scheduling-enabled=false"})
@ActiveProfiles("test")
class ApplicationContextIT {
    @Autowired IncidentRepository incidents;
    @Autowired AnomalyEventRepository anomalies;
    @Autowired DetectionRuleRepository rules;
    @Autowired DetectionPipeline pipeline;

    @Test
    void fullContextWiresAllModules() {
        assertThat(incidents).isNotNull();
        assertThat(anomalies).isNotNull();
        assertThat(rules.count()).isGreaterThan(0);   // seeded by V2 migration
        assertThat(pipeline).isNotNull();
    }
}
