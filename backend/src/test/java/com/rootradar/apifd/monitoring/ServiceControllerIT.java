package com.rootradar.apifd.monitoring;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rootradar.testsupport.mon.MonitoringIT;
import com.rootradar.testsupport.mon.TestHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** REST contract of /api/services/** (API_CONTRACTS section 2) against the real DB. */
@AutoConfigureMockMvc
class ServiceControllerIT extends MonitoringIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static String body(String name, String url) {
        return "{\"name\":\"" + name + "\",\"displayName\":\"T\",\"nodeType\":\"HTTP_SERVICE\",\"baseUrl\":\"" + url
                + "\",\"healthEndpoint\":\"/health\",\"telemetryEndpoint\":\"/internal/telemetry\","
                + "\"checkIntervalSeconds\":5,\"timeoutMs\":500}";
    }

    @Test
    void registerCheckListQueryUpdateDeleteLifecycle() throws Exception {
        try (TestHttpServer srv = new TestHttpServer().respond("/health", 200, "{\"status\":\"UP\"}").start()) {
            String name = uniqueName("api");
            String created = mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON)
                            .content(body(name, srv.baseUrl())))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", startsWith("/api/services/")))
                    .andExpect(jsonPath("$.name").value(name))
                    .andExpect(jsonPath("$.nodeType").value("HTTP_SERVICE"))
                    .andExpect(jsonPath("$.checkIntervalSeconds").value(5))
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.demo").value(false))
                    .andReturn().getResponse().getContentAsString();
            long id = mapper.readTree(created).get("id").asLong();

            mvc.perform(post("/api/services/" + id + "/check"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                    .andExpect(jsonPath("$.serviceName").value(name));

            mvc.perform(get("/api/services/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.service.name").value(name))
                    .andExpect(jsonPath("$.health.status").value("UP"))
                    .andExpect(jsonPath("$.availability24h").value(100.0));

            mvc.perform(get("/api/services")).andExpect(status().isOk());

            // health history: defaults to last hour, newest first, PageResponse shape
            mvc.perform(get("/api/services/" + id + "/health?size=5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(5))
                    .andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1));
            mvc.perform(get("/api/services/" + id + "/availability?bucketMinutes=10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalChecks").value(1))
                    .andExpect(jsonPath("$.uptimePercent").value(100.0));
            mvc.perform(get("/api/services/" + id + "/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope").value("SERVICE_INBOUND"))
                    .andExpect(jsonPath("$.source").value("INBOUND_TELEMETRY"))
                    .andExpect(jsonPath("$.bucketSeconds").value(10))
                    .andExpect(jsonPath("$.points", hasSize(0)));
            mvc.perform(get("/api/services/" + id + "/endpoints")).andExpect(status().isOk());
            mvc.perform(get("/api/services/" + id + "/dependencies/metrics"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.callerName").value(name));

            mvc.perform(put("/api/services/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":false,\"timeoutMs\":900,\"description\":\"d\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false))
                    .andExpect(jsonPath("$.timeoutMs").value(900))
                    .andExpect(jsonPath("$.baseUrl").value(srv.baseUrl()));

            mvc.perform(delete("/api/services/" + id)).andExpect(status().isNoContent());
            mvc.perform(get("/api/services/" + id)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void validationAndSsrfErrorsUseContractCodes() throws Exception {
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"nodeType\":\"HTTP_SERVICE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").exists());
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueName("ssrf"), "http://169.254.169.254")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("URL_NOT_ALLOWED"));
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueName("ssrf"), "file:///etc/passwd")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("URL_NOT_ALLOWED"));
        // HTTP node without URL
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + uniqueName("nourl") + "\",\"nodeType\":\"HTTP_SERVICE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // bad interval
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueName("x"), "http://localhost:1").replace("\"checkIntervalSeconds\":5", "\"checkIntervalSeconds\":0")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/services/999999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/services/999999/check")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/services/999999")).andExpect(status().isNotFound());
    }

    @Test
    void duplicateNameIs409AndDependencyOnlyRules() throws Exception {
        String name = uniqueName("dup");
        String b = body(name, "http://localhost:1");
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isCreated());
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        String dbName = uniqueName("db");
        String created = mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + dbName + "\",\"nodeType\":\"DEPENDENCY_ONLY\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.baseUrl").isEmpty()).andReturn()
                .getResponse().getContentAsString();
        long dbId = mapper.readTree(created).get("id").asLong();
        mvc.perform(post("/api/services/" + dbId + "/check")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/services/" + dbId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.health").isEmpty())
                .andExpect(jsonPath("$.availability24h").isEmpty());
        // DEPENDENCY_ONLY with a URL is invalid
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + uniqueName("db2") + "\",\"nodeType\":\"DEPENDENCY_ONLY\",\"baseUrl\":\"http://localhost:1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rangeAndPagingValidation() throws Exception {
        MonitoredService s = registerHttp("rng", "http://localhost:1", 500);
        String base = "/api/services/" + s.id();
        mvc.perform(get(base + "/health?from=2026-11-16T10:00:00Z&to=2026-11-16T09:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get(base + "/health?from=2026-01-01T00:00:00Z&to=2026-11-16T09:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get(base + "/health?size=500")).andExpect(status().isBadRequest());
        mvc.perform(get(base + "/health?from=yesterday")).andExpect(status().isBadRequest());
        mvc.perform(get(base + "/metrics?from=2026-11-01T00:00:00Z&to=2026-11-02T00:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE")); // 8640 raw points > 2000
        mvc.perform(get(base + "/metrics?from=2026-11-01T00:00:00Z&to=2026-11-02T00:00:00Z&bucketSeconds=60"))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/metrics?bucketSeconds=15")).andExpect(status().isBadRequest());
        mvc.perform(get(base + "/availability?bucketMinutes=0")).andExpect(status().isBadRequest());
    }

    @Test
    void metricsEndpointsReturnStoredTelemetry() throws Exception {
        MonitoredService s = registerHttp("met", "http://localhost:1", 500);
        java.time.Instant t0 = java.time.Instant.parse("2026-11-10T08:00:00Z");
        for (int i = 0; i < 3; i++) {
            java.time.Instant ws = t0.plusSeconds(10L * i);
            jdbc.update("insert into telemetry_snapshots (service_id, collected_at, window_start, window_end, window_seconds, method, endpoint,"
                            + " request_count, error_count, avg_ms, p50_ms, p95_ms, p99_ms, max_ms) values (?,?,?,?,10,?,?,10,1,20,18,40,50,60)",
                    s.id(), java.sql.Timestamp.from(ws), java.sql.Timestamp.from(ws), java.sql.Timestamp.from(ws.plusSeconds(10)),
                    i % 2 == 0 ? "*" : "*", "*");
        }
        jdbc.update("insert into telemetry_snapshots (service_id, collected_at, window_start, window_end, window_seconds, method, endpoint,"
                        + " request_count, error_count, avg_ms, p50_ms, p95_ms, p99_ms, max_ms) values (?,?,?,?,10,'POST','/api/x',4,0,5,5,6,6,7)",
                s.id(), java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0.plusSeconds(10)));
        String q = "?from=2026-11-10T08:00:00Z&to=2026-11-10T08:01:00Z";
        mvc.perform(get("/api/services/" + s.id() + "/metrics" + q))
                .andExpect(status().isOk()).andExpect(jsonPath("$.points", hasSize(3)))
                .andExpect(jsonPath("$.points[0].requestCount").value(10))
                .andExpect(jsonPath("$.points[0].p95Ms").value(40.0))
                .andExpect(jsonPath("$.points[0].windowStart").value("2026-11-10T08:00:00Z"));
        mvc.perform(get("/api/services/" + s.id() + "/metrics" + q + "&endpoint=/api/x&method=POST"))
                .andExpect(jsonPath("$.scope").value("ENDPOINT")).andExpect(jsonPath("$.points", hasSize(1)))
                .andExpect(jsonPath("$.endpoint").value("/api/x"));
        mvc.perform(get("/api/services/" + s.id() + "/metrics" + q + "&bucketSeconds=30"))
                .andExpect(jsonPath("$.points", hasSize(1))).andExpect(jsonPath("$.points[0].requestCount").value(30))
                .andExpect(jsonPath("$.bucketSeconds").value(30));
        mvc.perform(get("/api/services/" + s.id() + "/endpoints" + q))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].endpoint").value("/api/x"))
                .andExpect(jsonPath("$[0].summary.requestCount").value(4));
        JsonNode overview = mapper.readTree(mvc.perform(get("/api/services/" + s.id())).andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(overview.get("metrics").isNull(), "no traffic in the last 5 minutes -> null");
    }
}
