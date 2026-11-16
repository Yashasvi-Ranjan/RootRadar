package com.rootradar.apifd.incident;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rootradar.apifd.anomaly.store.ServiceLookup;
import com.rootradar.apifd.graph.GraphNode;
import com.rootradar.apifd.incident.persistence.DiagnosisEntity;
import com.rootradar.apifd.incident.persistence.DiagnosisRepository;
import com.rootradar.apifd.incident.persistence.FactorEntity;
import com.rootradar.apifd.incident.persistence.FactorRepository;
import com.rootradar.apifd.incident.persistence.FindingEntity;
import com.rootradar.apifd.incident.persistence.FindingRepository;
import com.rootradar.apifd.rca.DiagnosisInput;
import com.rootradar.apifd.rca.DiagnosisResult;
import com.rootradar.apifd.rca.RootCauseAnalyzer;
import com.rootradar.apifd.rca.ScoreFactor;
import com.rootradar.apifd.rca.ServiceFinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls the {@link RootCauseAnalyzer} and persists its result (diagnosis + findings + factors). It never alters scores.
 * The evidence signature (stored in {@code input_snapshot.evidenceSignature}) lets the caller skip re-analysis while
 * the evidence set has not changed.
 */
@Component
class IncidentDiagnoser {
    private static final Logger log = LoggerFactory.getLogger(IncidentDiagnoser.class);
    static final String SIGNATURE_KEY = "evidenceSignature";

    record Outcome(DiagnosisEntity entity, DiagnosisResult result) {}

    private final RootCauseAnalyzer analyzer;
    private final DiagnosisRepository diagnoses;
    private final FindingRepository findings;
    private final FactorRepository factors;
    private final ObjectMapper mapper;

    IncidentDiagnoser(RootCauseAnalyzer analyzer, DiagnosisRepository diagnoses, FindingRepository findings,
                      FactorRepository factors, ObjectMapper mapper) {
        this.analyzer = analyzer;
        this.diagnoses = diagnoses;
        this.findings = findings;
        this.factors = factors;
        this.mapper = mapper;
    }

    Optional<DiagnosisEntity> latest(long incidentId) {
        return diagnoses.findFirstByIncidentIdOrderByAnalyzedAtDescIdDesc(incidentId);
    }

    /**
     * Stable description of the evidence the analysis would be based on: the firing anomalies (fingerprint, severity)
     * and each node's verdict and health. Value drift inside the same verdict does not change it.
     */
    static String signature(DiagnosisInput input) {
        String anomalies = input.anomalies().stream().map(a -> a.fingerprint() + ":" + a.severity()).sorted()
                .collect(Collectors.joining(","));
        String observations = input.observations().stream()
                .map(o -> o.serviceName() + ":" + o.state() + ":" + o.healthStatus()).sorted()
                .collect(Collectors.joining(","));
        return anomalies + "#" + observations;
    }

    /** Runs the analyzer and stores the result; empty when the analyzer failed (the incident is kept regardless). */
    Optional<Outcome> diagnose(long incidentId, DiagnosisInput input, ServiceLookup.Snapshot services) {
        DiagnosisResult result;
        try {
            result = analyzer.analyze(input);
        } catch (RuntimeException ex) {
            log.error("Root cause analysis failed for incident {}", incidentId, ex);
            return Optional.empty();
        }
        if (result == null) return Optional.empty();

        DiagnosisEntity d = new DiagnosisEntity();
        d.incidentId = incidentId;
        d.analyzedAt = result.analyzedAt() != null ? result.analyzedAt() : input.analyzedAt();
        d.modelVersion = result.modelVersion() == null ? "unknown" : result.modelVersion();
        d.probableCauseServiceId = services.idOf(result.probableCauseService()).orElse(null);
        d.topScore = result.topScore();
        d.conclusive = result.conclusive();
        d.summary = result.summary() == null ? "" : result.summary();
        d.caveats = result.caveats() == null ? List.of() : new ArrayList<>(result.caveats());
        d.inputSnapshot = snapshot(input);
        d = diagnoses.save(d);

        for (ServiceFinding f : result.findings() == null ? List.<ServiceFinding>of() : result.findings()) {
            Long serviceId = services.idOf(f.serviceName()).orElse(null);
            if (serviceId == null) {
                log.warn("Diagnosis for incident {} mentions unknown service '{}'; finding not stored", incidentId,
                        f.serviceName());
                continue;
            }
            FindingEntity fe = new FindingEntity();
            fe.diagnosisId = d.id;
            fe.serviceId = serviceId;
            fe.rank = f.rank();
            fe.classification = f.classification();
            fe.score = Math.max(0, Math.min(100, f.score()));
            fe.firstAbnormalAt = f.firstAbnormalAt();
            fe.evidence = f.evidence() == null ? List.of() : new ArrayList<>(f.evidence());
            fe = findings.save(fe);
            List<FactorEntity> rows = new ArrayList<>();
            int seq = 0;
            for (ScoreFactor sf : f.factors() == null ? List.<ScoreFactor>of() : f.factors()) {
                FactorEntity x = new FactorEntity();
                x.findingId = fe.id;
                x.seq = seq++;
                x.ruleKey = sf.ruleKey();
                x.label = sf.label();
                x.points = sf.points();
                x.maxPoints = sf.maxPoints();
                x.fired = sf.fired();
                x.detail = sf.detail();
                rows.add(x);
            }
            factors.saveAll(rows);
        }
        return Optional.of(new Outcome(d, result));
    }

    private Map<String, Object> snapshot(DiagnosisInput input) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analyzedAt", input.analyzedAt().toString());
        m.put(SIGNATURE_KEY, signature(input));
        m.put("graph", Map.of(
                "nodes", input.graph().nodes().stream().map(GraphNode::name).toList(),
                "edges", input.graph().edges().stream().map(e -> e.from() + " -> " + e.to()).toList()));
        m.put("anomalies", mapper.convertValue(input.anomalies(), new TypeReference<List<Map<String, Object>>>() {}));
        m.put("observations",
                mapper.convertValue(input.observations(), new TypeReference<List<Map<String, Object>>>() {}));
        return m;
    }
}
