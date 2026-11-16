package com.rootradar.demo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Real PostgreSQL integration test (skipped when the local database is not reachable): dbLatencyMs executes
 * pg_sleep inside the measured JDBC call, so the "database" dependency latency rises; the endpoint latency rises too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class PaymentDatabaseFaultTest {
    @BeforeAll
    static void requireDb() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/apifd");
        String user = System.getenv().getOrDefault("DB_USER", "apifd");
        String pw = System.getenv().getOrDefault("DB_PASSWORD", "apifd_dev");
        boolean ok;
        try (Connection c = DriverManager.getConnection(url, user, pw)) {
            ok = c.isValid(2);
        } catch (Exception e) {
            ok = false;
        }
        Assumptions.assumeTrue(ok, "PostgreSQL not reachable: skipping");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    private void pay() {
        http().post().uri("/api/payments").contentType(MediaType.APPLICATION_JSON)
                .body("{\"orderId\":\"it-1\",\"amount\":5.00}").retrieve().toBodilessEntity();
    }

    private JsonNode lastWindowDb() throws Exception {
        Thread.sleep(10_000 - (System.currentTimeMillis() % 10_000) + 200);
        JsonNode ws = mapper.readTree(http().get().uri("/internal/telemetry").retrieve().body(String.class))
                .get("windows");
        JsonNode last = ws.get(ws.size() - 1);
        assertThat(last.get("dependencies")).isNotEmpty();
        JsonNode d = last.get("dependencies").get(0);
        assertThat(d.get("name").asText()).isEqualTo("database");
        assertThat(d.get("type").asText()).isEqualTo("JDBC");
        return d;
    }

    @Test
    void dbLatencyInjectionRaisesMeasuredDatabaseLatency() throws Exception {
        Thread.sleep(10_000 - (System.currentTimeMillis() % 10_000) + 50);
        pay();
        pay();
        JsonNode normal = lastWindowDb();
        assertThat(normal.get("count").asLong()).isEqualTo(2);
        assertThat(normal.get("p95Ms").asDouble()).isLessThan(300);

        http().put().uri("/admin/faults").header("X-Admin-Token", "demo-admin-token")
                .contentType(MediaType.APPLICATION_JSON).body("{\"dbLatencyMs\":500}").retrieve().toBodilessEntity();
        Thread.sleep(10_000 - (System.currentTimeMillis() % 10_000) + 50);
        pay();
        pay();
        JsonNode slow = lastWindowDb();
        assertThat(slow.get("p95Ms").asDouble()).isGreaterThanOrEqualTo(500);
        assertThat(slow.get("errorCount").asLong()).isZero();
        http().delete().uri("/admin/faults").header("X-Admin-Token", "demo-admin-token").retrieve().toBodilessEntity();
    }
}
