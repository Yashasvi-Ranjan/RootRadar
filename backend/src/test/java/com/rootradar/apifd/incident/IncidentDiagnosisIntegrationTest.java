package com.rootradar.apifd.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.ServiceRef;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.rca.FindingClassification;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Diagnosis persistence: findings, factors, root-cause denormalisation, re-diagnosis rule, analyzer failure. */
class IncidentDiagnosisIntegrationTest extends AbstractIncidentIntegrationTest {

    @Test
    void diagnosisIsPersistedWithFindingsFactorsAndDenormalisedRootCause() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        IncidentSummary s = only();
        assertThat(s.rootCause()).isEqualTo(new ServiceRef(ids.get(PAYMENT), PAYMENT));
        assertThat(s.rootCauseScore()).isEqualTo(85);
        assertThat(s.rootCauseSummary()).contains("probable cause");
        assertThat(s.title()).isEqualTo("Elevated latency in " + PAYMENT);
        // the analyzer reported order and gateway as AFFECTED callers -> they join the impacted set
        assertThat(s.impactedServices()).extracting(ServiceRef::name).containsExactlyInAnyOrder(PAYMENT, ORDER, GATEWAY);

        DiagnosisView d = incidents.latestDiagnosis(s.id()).orElseThrow();
        assertThat(d.diagnosis().modelVersion()).isEqualTo("fake-1.0");
        assertThat(d.diagnosis().probableCauseService()).isEqualTo(PAYMENT);
        assertThat(d.diagnosis().findings()).hasSize(3);
        var top = d.diagnosis().findings().get(0);
        assertThat(top.serviceName()).isEqualTo(PAYMENT);
        assertThat(top.classification()).isEqualTo(FindingClassification.PROBABLE_CAUSE);
        assertThat(top.score()).isEqualTo(85);
        assertThat(top.factors()).extracting(f -> f.ruleKey()).containsExactly("LATENCY_ANOMALY",
                "DOWNSTREAM_HEALTHY", "ERROR_RATE");
        assertThat(top.factors()).extracting(f -> f.points()).containsExactly(30, 25, 0);
        assertThat(top.factors().get(2).fired()).isFalse();
        assertThat(top.evidence()).containsExactly("p95 2000 ms vs baseline 45 ms");
        assertThat(d.diagnosis().caveats()).isNotEmpty();
        assertThat(d.diagnosis().findings()).extracting(f -> f.rank()).containsExactly(1, 2, 3);

        // snapshot for reproducibility was stored
        String snapshot = jdbc.queryForObject("SELECT input_snapshot::text FROM incident_diagnoses WHERE id = ?",
                String.class, d.id());
        assertThat(snapshot).contains("evidenceSignature").contains("observations").contains(PAYMENT);
        assertThat(timelineTypes(s.id())).contains(TimelineEventType.DIAGNOSIS_UPDATED);
    }

    @Test
    void unchangedEvidenceIsNotReanalysedButChangedEvidenceIs() {
        incidents.applyDetection(firing(0, Severity.WARNING, PAYMENT));
        incidents.applyDetection(firing(1, Severity.WARNING, PAYMENT));
        incidents.applyDetection(firing(2, Severity.WARNING, PAYMENT));
        assertThat(rca.calls.get()).as("same evidence, one analysis").isEqualTo(1);
        long id = only().id();
        assertThat(incidents.diagnoses(id)).hasSize(1);

        // a second service becomes anomalous: evidence changed -> second diagnosis, history kept newest first
        incidents.applyDetection(firing(3, Severity.WARNING, PAYMENT, GATEWAY));
        assertThat(rca.calls.get()).isEqualTo(2);
        List<DiagnosisView> history = incidents.diagnoses(id);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).diagnosis().analyzedAt()).isAfterOrEqualTo(history.get(1).diagnosis().analyzedAt());
        assertThat(rca.lastInput.anomalies()).hasSize(2);
        // observations cover the nodes connected to the incident's anomalies, healthy ones too (the database is
        // evidence); an unrelated isolated node is NOT included (QA defect D1)
        assertThat(rca.lastInput.observations()).extracting(o -> o.serviceName())
                .containsExactlyInAnyOrder(GATEWAY, ORDER, PAYMENT, DATABASE);
        assertThat(rca.lastInput.observations()).filteredOn(o -> o.serviceName().equals(PAYMENT))
                .singleElement().satisfies(o -> assertThat(o.state()).isEqualTo(SeriesState.ANOMALOUS));
    }

    @Test
    void recoveringIncidentKeepsItsLastDiagnosis() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        incidents.applyDetection(quiet(1, SeriesState.NORMAL, PAYMENT));
        assertThat(only().status()).isEqualTo(IncidentStatus.RECOVERING);
        assertThat(only().rootCause().name()).isEqualTo(PAYMENT);
        assertThat(rca.calls.get()).isEqualTo(1);
    }

    @Test
    void analyzerFailureDoesNotLoseTheIncident() {
        rca.failing = true;
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        IncidentSummary s = only();
        assertThat(s.rootCause()).isNull();
        assertThat(incidents.latestDiagnosis(s.id())).isEmpty();
        assertThat(incidents.getDetail(s.id()).anomalies()).hasSize(1);

        rca.failing = false;
        incidents.applyDetection(firing(1, Severity.CRITICAL, PAYMENT));
        assertThat(only().rootCause().name()).isEqualTo(PAYMENT);
    }
}
