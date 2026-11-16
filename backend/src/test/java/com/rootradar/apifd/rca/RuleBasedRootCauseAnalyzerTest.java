package com.rootradar.apifd.rca;

import static com.rootradar.apifd.rca.RcaTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.graph.DependencyGraph;
import com.rootradar.apifd.graph.GraphEdge;
import com.rootradar.apifd.graph.GraphNode;
import com.rootradar.apifd.graph.InvalidGraphException;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.rca.engine.RuleBasedRootCauseAnalyzer;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedRootCauseAnalyzerTest {
    private final RuleBasedRootCauseAnalyzer analyzer = new RuleBasedRootCauseAnalyzer();
    private static final NodeType H = NodeType.HTTP_SERVICE, D = NodeType.DEPENDENCY_ONLY;

    private DiagnosisInput paymentSlowdown() {
        List<AnomalyEvent> a = List.of(
                latency(PAY, null, T0, 2010, 45), latency(ORD, null, T0, 2080, 60), latency(GW, null, T0.plusSeconds(10), 2120, 80),
                latency(PAY, ORD, T0, 2020, 45), latency(ORD, GW, T0.plusSeconds(10), 2090, 60));
        List<ServiceObservation> o = List.of(
                anomalousObs(GW, H, 2120, 80, 0.01, T0.plusSeconds(10)), anomalousObs(ORD, H, 2080, 60, 0.01, T0),
                anomalousObs(PAY, H, 2010, 45, 0.01, T0), normalObs(DB, D, 12, 11));
        return input(demoGraph(), a, o);
    }

    @Test
    void paymentSlowdownCascadeRanksPaymentFirstWithDbNormalEvidence() {
        DiagnosisResult r = analyzer.analyze(paymentSlowdown());
        assertEquals(PAY, r.probableCauseService());
        assertTrue(r.conclusive());
        assertEquals(PAY, r.findings().get(0).serviceName());
        assertEquals(FindingClassification.PROBABLE_CAUSE, find(r, PAY).classification());
        assertEquals(FindingClassification.AFFECTED, find(r, ORD).classification());
        assertEquals(FindingClassification.AFFECTED, find(r, GW).classification());
        assertEquals(90, find(r, PAY).score());
        assertEquals(45, find(r, ORD).score());
        assertEquals(30, find(r, GW).score());
        assertTrue(find(r, PAY).evidence().stream().anyMatch(e -> e.contains("'database' is NORMAL")));
        assertTrue(r.findings().stream().noneMatch(f -> f.serviceName().equals(DB)), "healthy DB is evidence, not a finding");
        assertEquals(find(r, PAY).factors().stream().mapToInt(ScoreFactor::points).sum(), find(r, PAY).score());
        assertTrue(r.caveats().stream().anyMatch(c -> c.contains("not probabilit")));
    }

    @Test
    void databaseSlowdownRanksDatabaseFirstAndRootIsLeaf() {
        List<AnomalyEvent> a = List.of(latency(DB, PAY, T0, 900, 10), latency(PAY, null, T0, 950, 45),
                latency(ORD, null, T0, 1000, 60), latency(GW, null, T0, 1050, 80));
        List<ServiceObservation> o = List.of(anomalousObs(GW, H, 1050, 80, 0.01, T0), anomalousObs(ORD, H, 1000, 60, 0.01, T0),
                anomalousObs(PAY, H, 950, 45, 0.01, T0), anomalousObs(DB, D, 900, 10, 0.01, T0));
        DiagnosisResult r = analyzer.analyze(input(demoGraph(), a, o));
        assertEquals(DB, r.probableCauseService());
        assertEquals(FindingClassification.AFFECTED, find(r, PAY).classification());
        assertTrue(r.caveats().stream().anyMatch(c -> c.contains("no direct health check")));
    }

    @Test
    void errorCascadeRanksPayment() {
        List<AnomalyEvent> a = List.of(errors(PAY, null, T0, 0.7), errors(ORD, null, T0, 0.6), errors(GW, null, T0, 0.6),
                errors(PAY, ORD, T0, 0.7));
        List<ServiceObservation> o = List.of(anomalousObs(GW, H, 100, 80, 0.6, T0), anomalousObs(ORD, H, 70, 60, 0.6, T0),
                anomalousObs(PAY, H, 50, 45, 0.7, T0), normalObs(DB, D, 12, 11));
        DiagnosisResult r = analyzer.analyze(input(demoGraph(), a, o));
        assertEquals(PAY, r.probableCauseService());
        assertEquals(FindingClassification.AFFECTED, find(r, ORD).classification());
        assertTrue(find(r, PAY).factors().stream().anyMatch(f -> f.ruleKey().equals("ERROR_RATE_ANOMALY") && f.fired()));
    }

    @Test
    void onsetOrderBeyondToleranceMakesDownstreamLaterAmbiguousNotAffected() {
        // order broke 120 s before payment: order is not "explained" by payment's later anomaly
        List<AnomalyEvent> a = List.of(latency(ORD, null, T0, 800, 60), latency(PAY, null, T0.plusSeconds(120), 900, 45));
        List<ServiceObservation> o = List.of(normalObs(GW, H, 80, 80), anomalousObs(ORD, H, 800, 60, 0.01, T0),
                anomalousObs(PAY, H, 900, 45, 0.01, T0.plusSeconds(120)), normalObs(DB, D, 12, 11));
        DiagnosisResult r = analyzer.analyze(input(demoGraph(), a, o));
        assertNotEquals(FindingClassification.AFFECTED, find(r, ORD).classification());
        assertTrue(find(r, ORD).factors().stream().anyMatch(f -> f.ruleKey().equals("DOWNSTREAM_ANOMALOUS") && f.points() == -10));
        assertTrue(find(r, PAY).factors().stream().anyMatch(f -> f.ruleKey().equals("EARLIEST_ONSET") && f.points() == 0));
    }

    @Test
    void closeCompetingCandidatesAreReportedAmbiguousNotPickedArbitrarily() {
        // order precedes payment by 60 s (partial onset credit for payment); weak payment evidence keeps scores within margin
        List<AnomalyEvent> a = List.of(latency(ORD, null, T0, 800, 60), latency(ORD, GW, T0, 800, 60),
                latency(PAY, null, T0.plusSeconds(60), 130, 80));
        List<ServiceObservation> o = List.of(anomalousObs(GW, H, 800, 80, 0.01, T0), anomalousObs(ORD, H, 800, 60, 0.01, T0),
                anomalousObs(PAY, H, 130, 80, 0.01, T0.plusSeconds(60)), normalObs(DB, D, 12, 11));
        DiagnosisResult r = analyzer.analyze(input(demoGraph(), a, o));
        int diff = Math.abs(find(r, ORD).score() - find(r, PAY).score());
        assertTrue(diff <= 5, "test setup should produce a near tie, diff=" + diff);
        assertFalse(r.conclusive());
        assertNull(r.probableCauseService());
        assertTrue(r.caveats().stream().anyMatch(c -> c.startsWith("Ambiguous")));
        assertTrue(r.summary().startsWith("Inconclusive"));
    }

    @Test
    void insufficientEvidenceWhenBaselineMissingEverywhere() {
        List<ServiceObservation> o = List.of(noData(GW, H), insufficient(ORD, H, 300), insufficient(PAY, H, 310), noData(DB, D));
        List<AnomalyEvent> a = List.of(anomaly(com.rootradar.apifd.anomaly.AnomalyType.LATENCY, PAY, null,
                com.rootradar.apifd.common.Severity.WARNING, T0, 310, null));
        DiagnosisResult r = analyzer.analyze(input(demoGraph(), a, o));
        assertFalse(r.conclusive());
        assertNull(r.probableCauseService());
        assertEquals(FindingClassification.INSUFFICIENT_EVIDENCE, find(r, DB).classification());
        assertEquals(FindingClassification.INSUFFICIENT_EVIDENCE, find(r, GW).classification());
        assertTrue(find(r, PAY).score() < 60);
    }

    @Test
    void noAnomaliesGivesEmptyInconclusiveResult() {
        DiagnosisResult r = analyzer.analyze(input(demoGraph(), List.of(), List.of(normalObs(PAY, H, 40, 45))));
        assertTrue(r.findings().isEmpty());
        assertFalse(r.conclusive());
        assertNull(r.topScore());
    }

    @Test
    void missingTelemetryOfCauseIsPenalisedButCallersCorroborate() {
        List<AnomalyEvent> a = List.of(latency(PAY, ORD, T0, 2000, 45), latency(ORD, null, T0, 2050, 60));
        List<ServiceObservation> o = List.of(normalObs(GW, H, 80, 80), anomalousObs(ORD, H, 2050, 60, 0.01, T0),
                noData(PAY, H), normalObs(DB, D, 12, 11));
        DiagnosisResult r = analyzer.analyze(input(demoGraph(), a, o));
        ServiceFinding p = find(r, PAY);
        assertTrue(p.factors().stream().anyMatch(f -> f.ruleKey().equals("NO_OWN_TELEMETRY") && f.points() == -10));
        assertTrue(r.caveats().stream().anyMatch(c -> c.contains("no telemetry of its own")));
    }

    @Test
    void independentSimultaneousFaultsAreBothReported() {
        DependencyGraph g = DependencyGraph.of(
                List.of(new GraphNode(GW, H), new GraphNode(ORD, H), new GraphNode("search-service", H)),
                List.of(new GraphEdge(GW, ORD), new GraphEdge(GW, "search-service")));
        List<AnomalyEvent> a = List.of(latency(ORD, null, T0, 900, 60), latency("search-service", null, T0, 700, 50),
                latency(GW, null, T0, 950, 80));
        List<ServiceObservation> o = List.of(anomalousObs(GW, H, 950, 80, 0.01, T0), anomalousObs(ORD, H, 900, 60, 0.01, T0),
                anomalousObs("search-service", H, 700, 50, 0.01, T0));
        DiagnosisResult r = analyzer.analyze(input(g, a, o));
        assertEquals(FindingClassification.PROBABLE_CAUSE, find(r, ORD).classification());
        assertEquals(FindingClassification.PROBABLE_CAUSE, find(r, "search-service").classification());
        assertEquals(FindingClassification.AFFECTED, find(r, GW).classification());
        assertFalse(r.conclusive());
        assertTrue(r.caveats().stream().anyMatch(c -> c.startsWith("Multiple independent")));
    }

    @Test
    void cyclesAreRejectedByGraphAndUnknownServicesAreIgnored() {
        assertThrows(InvalidGraphException.class, () -> demoGraph().withEdge(DB, GW));
        DiagnosisInput in = paymentSlowdown();
        List<AnomalyEvent> withGhost = new java.util.ArrayList<>(in.anomalies());
        withGhost.add(latency("ghost-service", null, T0, 500, 10));
        DiagnosisResult r = analyzer.analyze(input(in.graph(), withGhost, in.observations()));
        assertEquals(PAY, r.probableCauseService());
        assertTrue(r.caveats().stream().anyMatch(c -> c.contains("ghost-service")));
    }

    @Test
    void deterministicRegardlessOfInputOrder() {
        DiagnosisInput in = paymentSlowdown();
        DiagnosisResult a = analyzer.analyze(in);
        assertEquals(a, analyzer.analyze(in));
        List<AnomalyEvent> ra = new java.util.ArrayList<>(in.anomalies());
        java.util.Collections.reverse(ra);
        List<ServiceObservation> ro = new java.util.ArrayList<>(in.observations());
        java.util.Collections.reverse(ro);
        DiagnosisResult b = analyzer.analyze(input(in.graph(), ra, ro));
        assertEquals(a.findings().stream().map(f -> f.serviceName() + f.score() + f.classification()).toList(),
                b.findings().stream().map(f -> f.serviceName() + f.score() + f.classification()).toList());
    }
}
