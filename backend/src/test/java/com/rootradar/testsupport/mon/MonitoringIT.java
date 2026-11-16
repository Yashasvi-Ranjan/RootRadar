package com.rootradar.testsupport.mon;

import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.monitoring.RegisterServiceRequest;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Base class: shared Spring context (real PostgreSQL apifd_test), schedulers off, cleanup of own rows ("mon-t-*"). */
@SpringBootTest(classes = MonitoringTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"apifd.monitoring.scheduling-enabled=false", "apifd.telemetry.scheduling-enabled=false"})
@ActiveProfiles("test")
public abstract class MonitoringIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ServiceRegistry registry;
    @Autowired protected MonitoringTestApp.RecordingPublisher publisher;

    @BeforeEach
    @AfterEach
    void cleanOwnRows() {
        jdbc.update("delete from services where name like 'mon-t-%'");
        publisher.events.clear();
    }

    protected static String uniqueName(String base) {
        return "mon-t-" + base + "-" + SEQ.incrementAndGet();
    }

    protected MonitoredService registerHttp(String base, String baseUrl, int timeoutMs) {
        return registry.register(new RegisterServiceRequest(uniqueName(base), null, NodeType.HTTP_SERVICE, baseUrl,
                "/health", "/internal/telemetry", 10, timeoutMs, true, null));
    }

    protected MonitoredService registerDependencyOnly(String base) {
        return registry.register(new RegisterServiceRequest(uniqueName(base), null, NodeType.DEPENDENCY_ONLY, null,
                null, null, null, null, true, null));
    }
}
