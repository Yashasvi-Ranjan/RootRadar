package com.rootradar.apifd.incident;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.rootradar.apifd.anomaly.AnomalyDetector;
import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.anomaly.AnomalyType;
import com.rootradar.apifd.anomaly.DetectionMethod;
import com.rootradar.apifd.anomaly.DetectionResult;
import com.rootradar.apifd.anomaly.SeriesAssessment;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.graph.DependencyGraph;
import com.rootradar.apifd.graph.DependencyGraphService;
import com.rootradar.apifd.graph.GraphEdge;
import com.rootradar.apifd.graph.GraphNode;
import com.rootradar.apifd.monitoring.HealthQueryService;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import com.rootradar.apifd.rca.RootCauseAnalyzer;
import com.rootradar.apifd.realtime.SseEventPublisher;
import com.rootradar.apifd.telemetry.MetricScope;
import com.rootradar.apifd.telemetry.MetricsQueryService;
import com.rootradar.apifd.telemetry.SeriesKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Shared fixture for integration tests against the real PostgreSQL database {@code apifd_test}. The collaborators
 * owned by other agents (registry, graph, metrics, health, rules, detector, analyzer) are replaced by mocks backed by
 * small fakes; everything owned by the incident engineer (service, repositories, controllers, SSE) is real.
 * Test rows use the service-name prefix {@code t5-} and are removed before and after each test.
 */
@SpringBootTest(classes = AbstractIncidentIntegrationTest.TestApp.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class AbstractIncidentIntegrationTest {
    /**
     * Full application context WITHOUT other test suites' scan-restricting configurations (e.g. a
     * {@code @EnableJpaRepositories} on a test-support application class would otherwise hide our repositories).
     * Being nested in a test class, this configuration is itself invisible to other tests' component scans.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableScheduling
    @AutoConfigurationPackage(basePackages = "com.rootradar.apifd")
    @ConfigurationPropertiesScan("com.rootradar.apifd.config")
    @ComponentScan(basePackages = "com.rootradar.apifd", excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.rootradar\\.apifd\\.(Application|testsupport\\..*)")})
    static class TestApp {}

    public static final Instant BASE = Instant.parse("2026-11-16T10:00:00Z");
    public static final String GATEWAY = "t5-gateway";
    public static final String ORDER = "t5-order";
    public static final String PAYMENT = "t5-payment";
    public static final String DATABASE = "t5-database";
    public static final String ISOLATED = "t5-isolated";

    @MockitoBean ServiceRegistry registry;
    @MockitoBean DependencyGraphService graphService;
    @MockitoBean HealthQueryService healthQuery;
    @MockitoBean MetricsQueryService metrics;
    @MockitoBean AnomalyDetector detector;
    @MockitoBean RootCauseAnalyzer analyzer;
    @MockitoSpyBean protected SseEventPublisher publisher;

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected IncidentService incidents;

    protected final FakeRootCauseAnalyzer rca = new FakeRootCauseAnalyzer();
    protected final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void setUpFixture() {
        cleanDatabase();
        List<MonitoredService> all = new ArrayList<>();
        for (String name : List.of(GATEWAY, ORDER, PAYMENT, DATABASE, ISOLATED)) {
            boolean db = name.equals(DATABASE);
            Long id = jdbc.queryForObject("""
                    INSERT INTO services (name, display_name, node_type, base_url, health_endpoint)
                    VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, name, name,
                    db ? "DEPENDENCY_ONLY" : "HTTP_SERVICE", db ? null : "http://localhost:9", db ? null : "/health");
            ids.put(name, id);
            all.add(new MonitoredService(id, name, name, db ? NodeType.DEPENDENCY_ONLY : NodeType.HTTP_SERVICE,
                    null, null, null, 10, 2000, true, true, null, BASE, BASE));
        }
        when(registry.findAll()).thenReturn(all);
        when(graphService.currentGraph()).thenReturn(DependencyGraph.of(
                List.of(new GraphNode(GATEWAY, NodeType.HTTP_SERVICE), new GraphNode(ORDER, NodeType.HTTP_SERVICE),
                        new GraphNode(PAYMENT, NodeType.HTTP_SERVICE), new GraphNode(DATABASE, NodeType.DEPENDENCY_ONLY),
                        new GraphNode(ISOLATED, NodeType.HTTP_SERVICE)),
                List.of(new GraphEdge(GATEWAY, ORDER), new GraphEdge(ORDER, PAYMENT), new GraphEdge(PAYMENT, DATABASE))));
        when(analyzer.analyze(any())).thenAnswer(inv -> rca.analyze(inv.getArgument(0)));
    }

    @AfterEach
    void tearDownFixture() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM incidents WHERE dedup_key LIKE '%|t5-%'");
        jdbc.update("DELETE FROM anomaly_events WHERE fingerprint LIKE '%|t5-%'");
        jdbc.update("DELETE FROM services WHERE name LIKE 't5-%'");
    }

    // ---- builders

    public static Instant t(int evaluation) {
        return BASE.plusSeconds(10L * evaluation);
    }

    public static AnomalyEvent anomaly(String service, AnomalyType type, Severity severity, Instant detectedAt,
                                Instant firstBreachAt) {
        SeriesKey key = SeriesKey.inbound(service);
        String text = type == AnomalyType.LATENCY ? "p95 latency 2011 ms >= threshold" : "error rate 0.4 >= threshold";
        return new AnomalyEvent(AnomalyEvent.fingerprintOf(type, key), type, severity, service,
                MetricScope.SERVICE_INBOUND, null, null, null, null, DetectionMethod.STATIC_THRESHOLD,
                type == AnomalyType.LATENCY ? "latency-p95-default" : "error-rate-default", detectedAt, firstBreachAt,
                firstBreachAt, detectedAt, type == AnomalyType.LATENCY ? 2011.0 : 0.4, 46.0, 800.0, null, 3, 3, 30,
                text, Map.of("mad", 4.2));
    }

    public static AnomalyEvent latency(String service, Severity severity, Instant at) {
        return anomaly(service, AnomalyType.LATENCY, severity, at, at.minusSeconds(20));
    }

    public static SeriesAssessment assessment(String service, SeriesState state) {
        return new SeriesAssessment(SeriesKey.inbound(service), state, BASE, state == SeriesState.ANOMALOUS ? 3 : 0,
                state == SeriesState.NORMAL ? 5 : 0, 30, state == SeriesState.ANOMALOUS ? 2011.0 : 45.0, 46.0, 0.0,
                0.0, 100, state == SeriesState.ANOMALOUS ? BASE : null, null);
    }

    public static DetectionResult detection(Instant at, List<AnomalyEvent> anomalies, SeriesAssessment... assessments) {
        return new DetectionResult(at, anomalies, List.of(assessments));
    }

    /** One evaluation in which {@code service} fires a warning/critical latency anomaly. */
    public DetectionResult firing(int evaluation, Severity severity, String... services) {
        List<AnomalyEvent> events = new ArrayList<>();
        List<SeriesAssessment> as = new ArrayList<>();
        for (String s : services) {
            events.add(latency(s, severity, t(evaluation)));
            as.add(assessment(s, SeriesState.ANOMALOUS));
        }
        return new DetectionResult(t(evaluation), events, as);
    }

    public DetectionResult quiet(int evaluation, SeriesState state, String... services) {
        List<SeriesAssessment> as = new ArrayList<>();
        for (String s : services) as.add(assessment(s, state));
        return new DetectionResult(t(evaluation), List.of(), as);
    }

    public IncidentSummary only() {
        List<IncidentSummary> all = incidents.findActive();
        if (all.size() != 1) throw new AssertionError("expected exactly one active incident but found " + all.size());
        return all.get(0);
    }

    public int incidentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM incidents WHERE dedup_key LIKE '%|t5-%'", Integer.class);
    }

    public List<TimelineEventType> timelineTypes(long incidentId) {
        return incidents.timeline(incidentId).stream().map(TimelineEntry::eventType).toList();
    }
}
