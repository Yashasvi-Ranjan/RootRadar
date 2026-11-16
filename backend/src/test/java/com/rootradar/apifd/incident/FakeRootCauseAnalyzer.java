package com.rootradar.apifd.incident;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.rca.DiagnosisInput;
import com.rootradar.apifd.rca.DiagnosisResult;
import com.rootradar.apifd.rca.FindingClassification;
import com.rootradar.apifd.rca.RootCauseAnalyzer;
import com.rootradar.apifd.rca.ScoreFactor;
import com.rootradar.apifd.rca.ServiceFinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small deterministic stand-in for the real analyzer: the anomalous service with no anomalous downstream is the
 * probable cause (score 85); its callers are AFFECTED. Counts invocations so tests can assert re-diagnosis rules.
 */
public class FakeRootCauseAnalyzer implements RootCauseAnalyzer {
    public final AtomicInteger calls = new AtomicInteger();
    public volatile boolean failing;
    public volatile DiagnosisInput lastInput;

    @Override
    public DiagnosisResult analyze(DiagnosisInput input) {
        calls.incrementAndGet();
        lastInput = input;
        if (failing) throw new IllegalStateException("analyzer down");
        Set<String> subjects = new LinkedHashSet<>();
        for (AnomalyEvent a : input.anomalies()) subjects.add(a.subjectService());
        String cause = subjects.stream()
                .filter(s -> input.graph().downstream(s).stream().noneMatch(subjects::contains)).findFirst()
                .orElse(null);
        List<ServiceFinding> findings = new ArrayList<>();
        if (cause != null) {
            findings.add(new ServiceFinding(cause, 1, FindingClassification.PROBABLE_CAUSE, 85,
                    input.analyzedAt().minusSeconds(30),
                    List.of(new ScoreFactor("LATENCY_ANOMALY", "Latency anomaly", 30, 30, true, "p95 2000 ms vs 45 ms"),
                            new ScoreFactor("DOWNSTREAM_HEALTHY", "Downstream healthy", 25, 25, true, "database normal"),
                            new ScoreFactor("ERROR_RATE", "Error rate", 0, 20, false, "not fired")),
                    List.of("p95 2000 ms vs baseline 45 ms")));
            int rank = 2;
            for (String up : input.graph().upstream(cause)) {
                findings.add(new ServiceFinding(up, rank++, FindingClassification.AFFECTED, 20, null,
                        List.of(new ScoreFactor("UPSTREAM_IMPACT", "Upstream impact", 20, 20, true, "calls " + cause)),
                        List.of(up + " depends on " + cause)));
            }
        }
        return new DiagnosisResult(input.analyzedAt(), "fake-1.0", cause, cause == null ? null : 85, cause != null,
                cause == null ? "No cause" : cause + " is the probable cause", findings,
                List.of("Ranking is correlation-based."));
    }
}
