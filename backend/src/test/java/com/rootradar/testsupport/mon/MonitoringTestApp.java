package com.rootradar.testsupport.mon;

import com.rootradar.apifd.realtime.EventPublisher;
import com.rootradar.apifd.realtime.LiveEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal Spring Boot application for the monitoring/telemetry/graph integration tests: scans only the packages
 * owned by the monitoring engineer (plus common/config) and supplies a recording {@link EventPublisher}, so these
 * tests are independent of the incident/anomaly/realtime implementations. Real PostgreSQL (apifd_test) + Flyway.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ConfigurationPropertiesScan("com.rootradar.apifd.config")
@ComponentScan(basePackages = {"com.rootradar.apifd.monitoring", "com.rootradar.apifd.telemetry",
        "com.rootradar.apifd.graph", "com.rootradar.apifd.config", "com.rootradar.apifd.common"})
@EntityScan({"com.rootradar.apifd.monitoring.persistence", "com.rootradar.apifd.telemetry.persistence",
        "com.rootradar.apifd.graph.persistence"})
@EnableJpaRepositories({"com.rootradar.apifd.monitoring.persistence", "com.rootradar.apifd.telemetry.persistence",
        "com.rootradar.apifd.graph.persistence"})
public class MonitoringTestApp {

    /** Records every published event so tests can assert health.updated / telemetry.updated. */
    public static class RecordingPublisher implements EventPublisher {
        public final List<LiveEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LiveEvent event) {
            events.add(event);
        }
    }

    @Bean
    public RecordingPublisher recordingPublisher() {
        return new RecordingPublisher();
    }
}
