package com.rootradar.apifd.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.incident.AbstractIncidentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** SSE endpoint: framing, heartbeat on connect, type filter and live delivery of incident events. */
class StreamControllerIntegrationTest extends AbstractIncidentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SseHub hub;

    private MvcResult open(String types) throws Exception {
        var req = get("/api/stream");
        if (types != null) req = req.param("types", types);
        return mvc.perform(req).andExpect(request().asyncStarted()).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    @Test
    void connectSendsRetryAndImmediateHeartbeat() throws Exception {
        MvcResult r = open(null);
        assertThat(r.getResponse().getContentType()).startsWith("text/event-stream");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(body(r))
                .contains("retry:3000").contains("event:heartbeat").contains("\"type\":\"heartbeat\"")
                .contains("\"serverTime\""));
        assertThat(body(r)).containsPattern("(?m)^id:\\d+$");
    }

    @Test
    void incidentLifecycleEventsReachConnectedClientsWithWireNames() throws Exception {
        MvcResult r = open(null);
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(r))
                .contains("event:anomaly.detected").contains("event:incident.opened")
                .contains("event:diagnosis.updated").contains("\"change\":\"OPENED\"")
                .contains("\"probableCauseService\":\"" + PAYMENT + "\""));
        for (int i = 1; i <= 3; i++) incidents.applyDetection(quiet(i, SeriesState.NORMAL, PAYMENT));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(r))
                .contains("event:anomaly.cleared").contains("event:incident.updated")
                .contains("event:incident.resolved").contains("\"change\":\"RESOLVED\""));
    }

    @Test
    void typeFilterLimitsEventsButKeepsHeartbeats() throws Exception {
        MvcResult r = open("incident.resolved");
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        for (int i = 1; i <= 3; i++) incidents.applyDetection(quiet(i, SeriesState.NORMAL, PAYMENT));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(r)).contains("event:incident.resolved"));
        assertThat(body(r)).doesNotContain("event:incident.opened").doesNotContain("event:anomaly.detected")
                .contains("event:heartbeat");
    }

    @Test
    void everyEventTypeIsSerialisedWithEnvelope() throws Exception {
        MvcResult r = open(null);
        Instant now = Instant.parse("2026-11-16T10:00:00Z");
        for (LiveEventType type : LiveEventType.values()) {
            hub.publish(new LiveEvent(type, now, java.util.Map.of("probe", type.wireName())));
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            for (LiveEventType type : LiveEventType.values()) {
                assertThat(body(r)).contains("event:" + type.wireName());
            }
        });
        assertThat(body(r)).contains("\"timestamp\":\"2026-11-16T10:00:00Z\"").contains("\"probe\":\"health.updated\"");
    }

    @Test
    void unknownTypeFilterIsABadRequest() throws Exception {
        mvc.perform(get("/api/stream").param("types", "nope.event")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
