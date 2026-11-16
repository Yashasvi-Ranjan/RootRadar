package com.rootradar.apifd.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootradar.apifd.anomaly.AnomalyDetector;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** REST surface: filters, pagination, detail, history, anomalies, system info, error shape. */
class IncidentApiIntegrationTest extends AbstractIncidentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DetectionPipeline pipeline;

    /** Creates three RESOLVED incidents far enough apart (> reopen window) plus one open incident. */
    private void seedHistory() {
        int e = 0;
        for (String svc : new String[] {PAYMENT, ISOLATED, PAYMENT}) {
            incidents.applyDetection(firing(e, Severity.WARNING, svc));
            incidents.applyDetection(quiet(e + 1, SeriesState.NORMAL, svc));   // clears before confirmation
            e += 50;                                                            // 500 s > reopen window 300 s
        }
        incidents.applyDetection(firing(e, Severity.CRITICAL, ISOLATED));
    }

    @Test
    void listPaginatesSortsNewestFirstAndFilters() throws Exception {
        seedHistory();
        mvc.perform(get("/api/incidents").param("service", ISOLATED).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
        mvc.perform(get("/api/incidents").param("service", PAYMENT).param("status", "RESOLVED"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].detectedAt").value("2026-11-16T10:16:40Z"))
                .andExpect(jsonPath("$.items[1].detectedAt").value("2026-11-16T10:00:00Z"));
        mvc.perform(get("/api/incidents").param("service", PAYMENT).param("status", "ACTIVE,DETECTED"))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/incidents").param("service", ISOLATED).param("severity", "CRITICAL"))
                .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/incidents").param("service", ISOLATED).param("severity", "WARNING"))
                .andExpect(jsonPath("$.totalItems").value(1));
        // detectedAt range: only the first payment incident (10:00:00) falls into [09:59, 10:05)
        mvc.perform(get("/api/incidents").param("service", PAYMENT).param("from", "2026-11-16T09:59:00Z")
                        .param("to", "2026-11-16T10:05:00Z"))
                .andExpect(jsonPath("$.totalItems").value(1));
        // service filter matches the impacted set, not only the root cause: order is an AFFECTED caller of payment
        mvc.perform(get("/api/incidents").param("service", ORDER).param("status", "RESOLVED"))
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/incidents").param("service", "t5-does-not-exist"))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void invalidParametersAreRejectedWithTheUniformErrorShape() throws Exception {
        mvc.perform(get("/api/incidents").param("status", "BOGUS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/incidents").param("from", "2026-11-16T10:00:00Z").param("to", "2026-11-16T09:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get("/api/incidents").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/incidents/987654321")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/incidents/987654321/diagnosis")).andExpect(status().isNotFound());
        mvc.perform(get("/api/anomalies").param("status", "WHATEVER")).andExpect(status().isBadRequest());
    }

    @Test
    void activeEndpointOrdersBySeverityThenNewest() throws Exception {
        incidents.applyDetection(firing(0, Severity.WARNING, ISOLATED));
        incidents.applyDetection(firing(1, Severity.CRITICAL, PAYMENT));
        mvc.perform(get("/api/incidents/active")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.severity=='CRITICAL' && @.rootCause.name=='" + PAYMENT + "')]").exists());
        var active = incidents.findActive().stream()
                .filter(i -> i.impactedServices().stream().anyMatch(s -> s.name().startsWith("t5-"))).toList();
        assertThat(active.get(0).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void detailCarriesTimelineDiagnosisAndAnomalies() throws Exception {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        incidents.applyDetection(firing(1, Severity.CRITICAL, PAYMENT));
        long id = only().id();
        mvc.perform(get("/api/incidents/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.incident.id").value(id))
                .andExpect(jsonPath("$.incident.rootCauseScore").value(85))
                .andExpect(jsonPath("$.latestDiagnosis.probableCauseService").value(PAYMENT))
                .andExpect(jsonPath("$.latestDiagnosis.findings[0].factors[0].ruleKey").value("LATENCY_ANOMALY"))
                .andExpect(jsonPath("$.anomalies[0].fingerprint").value(com.rootradar.apifd.anomaly.AnomalyEvent.fingerprintOf(com.rootradar.apifd.anomaly.AnomalyType.LATENCY,
                        com.rootradar.apifd.telemetry.SeriesKey.inbound(PAYMENT))))
                .andExpect(jsonPath("$.anomalies[0].subjectService").value(PAYMENT))
                .andExpect(jsonPath("$.anomalies[0].status").value("OPEN"))
                .andExpect(jsonPath("$.timeline[0].eventType").value("INCIDENT_DETECTED"));
        mvc.perform(get("/api/incidents/" + id + "/timeline")).andExpect(jsonPath("$[0].incidentId").value(id));
        mvc.perform(get("/api/incidents/" + id + "/diagnosis")).andExpect(jsonPath("$.incidentId").value(id))
                .andExpect(jsonPath("$.modelVersion").value("fake-1.0"));
        mvc.perform(get("/api/incidents/" + id + "/diagnoses")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/incidents/" + id + "/anomalies")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anomalyHistoryIsPagedAndFilterable() throws Exception {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT, GATEWAY));
        incidents.applyDetection(quiet(1, SeriesState.NORMAL, PAYMENT, GATEWAY));
        mvc.perform(get("/api/anomalies").param("service", PAYMENT).param("status", "CLEARED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].clearedAt").isNotEmpty());
        mvc.perform(get("/api/anomalies").param("service", GATEWAY).param("status", "OPEN"))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/anomalies").param("service", PAYMENT).param("from", "2026-11-16T09:00:00Z")
                        .param("to", "2026-11-16T11:00:00Z").param("size", "1"))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void pipelineRunPersistsIncidentFromDetectorOutputAndSystemInfoReportsIt() throws Exception {
        mvc.perform(get("/api/system/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(false)).andExpect(jsonPath("$.version").value("0.1.0"))
                .andExpect(jsonPath("$.windowSeconds").value(10))
                .andExpect(jsonPath("$.detectionIntervalSeconds").value(10))
                .andExpect(jsonPath("$.serverTime").isNotEmpty());

        FakeAnomalyDetector fake = new FakeAnomalyDetector();
        fake.script = in -> detection(in.evaluatedAt(), java.util.List.of(latency(PAYMENT, Severity.CRITICAL, in.evaluatedAt())),
                assessment(PAYMENT, SeriesState.ANOMALOUS));
        when(detector.detect(any())).thenAnswer(inv -> fake.detect(inv.getArgument(0)));

        assertThat(pipeline.runOnce()).extracting(IncidentChange::type).contains(IncidentChangeType.OPENED);
        assertThat(fake.inputs).hasSize(1);
        assertThat(only().rootCause().name()).isEqualTo(PAYMENT);
        mvc.perform(get("/api/system/info")).andExpect(jsonPath("$.lastDetectionRunAt").isNotEmpty());
    }
}
