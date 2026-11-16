package com.rootradar.apifd.rca;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.anomaly.AnomalyType;
import com.rootradar.apifd.anomaly.DetectionMethod;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.graph.DependencyGraph;
import com.rootradar.apifd.graph.GraphEdge;
import com.rootradar.apifd.graph.GraphNode;
import com.rootradar.apifd.monitoring.HealthStatus;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.telemetry.MetricScope;
import com.rootradar.apifd.telemetry.MetricsSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class RcaTestSupport {
    static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    static final String GW = "gateway-service", ORD = "order-service", PAY = "payment-service", DB = "database";

    private RcaTestSupport() {}

    static DependencyGraph demoGraph() {
        return DependencyGraph.of(
                List.of(new GraphNode(GW, NodeType.HTTP_SERVICE), new GraphNode(ORD, NodeType.HTTP_SERVICE),
                        new GraphNode(PAY, NodeType.HTTP_SERVICE), new GraphNode(DB, NodeType.DEPENDENCY_ONLY)),
                List.of(new GraphEdge(GW, ORD), new GraphEdge(ORD, PAY), new GraphEdge(PAY, DB)));
    }

    static AnomalyEvent anomaly(AnomalyType type, String subject, String observedBy, Severity sev, Instant first,
                                double observed, Double baseline) {
        MetricScope scope = observedBy == null ? MetricScope.SERVICE_INBOUND : MetricScope.DEPENDENCY_CALL;
        return new AnomalyEvent(type + "|" + scope + "|" + subject + "|" + observedBy, type, sev, subject, scope,
                observedBy, null, null, observedBy == null ? null : subject, DetectionMethod.BASELINE_RATIO, "r",
                first.plusSeconds(60), first, first, first.plusSeconds(60), observed, baseline, null, null, 3, 3, 10,
                type + " on " + subject + " observed " + observed, Map.of());
    }

    static AnomalyEvent latency(String subject, String observedBy, Instant first, double ms, double base) {
        return anomaly(AnomalyType.LATENCY, subject, observedBy, Severity.CRITICAL, first, ms, base);
    }

    static AnomalyEvent errors(String subject, String observedBy, Instant first, double rate) {
        return anomaly(AnomalyType.ERROR_RATE, subject, observedBy, Severity.CRITICAL, first, rate, 0.01);
    }

    static ServiceObservation anomalousObs(String name, NodeType t, double cur, double base, double err, Instant first) {
        return new ServiceObservation(name, t, t == NodeType.HTTP_SERVICE ? HealthStatus.UP : HealthStatus.UNKNOWN,
                t == NodeType.HTTP_SERVICE ? MetricsSource.INBOUND_TELEMETRY : MetricsSource.CALLER_MEASUREMENT,
                SeriesState.ANOMALOUS, cur, base, err, 0.01, 200, first);
    }

    static ServiceObservation normalObs(String name, NodeType t, double cur, double base) {
        return new ServiceObservation(name, t, t == NodeType.HTTP_SERVICE ? HealthStatus.UP : HealthStatus.UNKNOWN,
                t == NodeType.HTTP_SERVICE ? MetricsSource.INBOUND_TELEMETRY : MetricsSource.CALLER_MEASUREMENT,
                SeriesState.NORMAL, cur, base, 0.01, 0.01, 200, null);
    }

    static ServiceObservation noData(String name, NodeType t) {
        return new ServiceObservation(name, t, HealthStatus.UNKNOWN, MetricsSource.NONE, SeriesState.NO_DATA,
                null, null, null, null, 0, null);
    }

    static ServiceObservation insufficient(String name, NodeType t, double cur) {
        return new ServiceObservation(name, t, HealthStatus.UP, MetricsSource.INBOUND_TELEMETRY,
                SeriesState.INSUFFICIENT_BASELINE, cur, null, 0.0, null, 50, null);
    }

    static DiagnosisInput input(DependencyGraph g, List<AnomalyEvent> a, List<ServiceObservation> o) {
        return new DiagnosisInput(T0.plusSeconds(120), g, a, o);
    }

    static ServiceFinding find(DiagnosisResult r, String name) {
        return r.findings().stream().filter(f -> f.serviceName().equals(name)).findFirst().orElseThrow();
    }
}
