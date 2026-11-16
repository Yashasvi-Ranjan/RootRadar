package com.rootradar.demo.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Real in-process integration test: the order-service runs on a random port, calls a real (stub) payment HTTP server,
 * and faults are injected through the real admin API. Proves latency injection raises measured latency and error
 * injection raises error counts in /internal/telemetry. Telemetry windows are polled via the real endpoint: we
 * read the in-progress window through a short wait until completion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class OrderFaultInjectionTest {
    private static HttpServer stub;
    private static final AtomicInteger stubCalls = new AtomicInteger();
    private static volatile int stubDelayMs = 0;
    private static volatile int stubStatus = 201;

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(Executors.newFixedThreadPool(8));
        stub.createContext("/api/payments", ex -> {
            stubCalls.incrementAndGet();
            try {
                Thread.sleep(stubDelayMs);
            } catch (InterruptedException ignored) {
            }
            byte[] b = "{\"paymentId\":42,\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(stubStatus, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("demo.downstream.url", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
        r.add("demo.downstream.read-timeout-ms", () -> "1000");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    @BeforeEach
    void reset() {
        stubDelayMs = 0;
        stubStatus = 201;
        http().delete().uri("/admin/faults").header("X-Admin-Token", "demo-admin-token").retrieve().toBodilessEntity();
    }

    private int order() {
        try {
            return http().post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                    .body("{\"customerId\":\"c-1\",\"items\":[{\"sku\":\"A-1\",\"quantity\":1}],\"amount\":19.99}")
                    .retrieve().toBodilessEntity().getStatusCode().value();
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    private void fault(String json) {
        http().put().uri("/admin/faults").header("X-Admin-Token", "demo-admin-token")
                .contentType(MediaType.APPLICATION_JSON).body(json).retrieve().toBodilessEntity();
    }

    /** Starts a phase right after a window boundary so the phase's samples land in a single window. */
    private void align() throws Exception {
        Thread.sleep(10_000 - (System.currentTimeMillis() % 10_000) + 50);
    }

    /** Aggregates all completed windows of the order endpoint after waiting for the current window to close. */
    private double[] stats() throws Exception {
        long ms = 10_000 - (System.currentTimeMillis() % 10_000) + 200;
        Thread.sleep(ms);
        JsonNode t = mapper.readTree(http().get().uri("/internal/telemetry").retrieve().body(String.class));
        long count = 0, errors = 0;
        double max = 0;
        long depErrors = 0, timeouts = 0;
        // only the last completed window (this phase)
        JsonNode ws = t.get("windows");
        for (int i = Math.max(0, ws.size() - 1); i < ws.size(); i++) {
            for (JsonNode e : ws.get(i).get("endpoints")) {
                if (e.get("endpoint").asText().equals("/api/orders") && e.get("method").asText().equals("POST")) {
                    count += e.get("count").asLong();
                    errors += e.get("errorCount").asLong();
                    max = Math.max(max, e.get("p95Ms").asDouble());
                }
            }
            for (JsonNode d : ws.get(i).get("dependencies")) {
                depErrors += d.get("errorCount").asLong();
                timeouts += d.get("timeoutCount").asLong();
            }
        }
        return new double[] {count, errors, max, depErrors, timeouts};
    }

    @Test
    void baselineThenLatencyThenErrorsThenTimeout() throws Exception {
        // baseline
        align();
        for (int i = 0; i < 5; i++) {
            assertThat(order()).isEqualTo(201);
        }
        double[] base = stats();
        assertThat(base[0]).isEqualTo(5);
        assertThat(base[1]).isZero();
        assertThat(base[2]).isLessThan(300);

        // injected service latency: measured latency rises, no errors
        align();
        fault("{\"latencyMs\":400}");
        for (int i = 0; i < 3; i++) {
            assertThat(order()).isEqualTo(201);
        }
        double[] slow = stats();
        assertThat(slow[0]).isEqualTo(3);
        assertThat(slow[2]).isGreaterThanOrEqualTo(400);
        assertThat(slow[1]).isZero();

        // injected 500s: error count rises
        align();
        fault("{\"errorRate\":1.0,\"errorStatus\":500}");
        for (int i = 0; i < 4; i++) {
            assertThat(order()).isEqualTo(500);
        }
        double[] err = stats();
        assertThat(err[0]).isEqualTo(4);
        assertThat(err[1]).isEqualTo(4);

        // payment slower than the order->payment read timeout (1 s here): 502 and a dependency timeout
        http().delete().uri("/admin/faults").header("X-Admin-Token", "demo-admin-token").retrieve().toBodilessEntity();
        align();
        stubDelayMs = 1500;
        assertThat(order()).isEqualTo(502);
        double[] to = stats();
        assertThat(to[1]).isEqualTo(1);
        assertThat(to[3]).isEqualTo(1);
        assertThat(to[4]).isEqualTo(1);
    }

    @Test
    void unavailableAndHealthSemantics() {
        fault("{\"unavailable\":true}");
        assertThat(order()).isEqualTo(503);
        assertThat(http().get().uri("/health").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        fault("{\"unavailable\":true,\"affectsHealth\":true}");
        try {
            http().get().uri("/health").retrieve().toBodilessEntity();
            org.junit.jupiter.api.Assertions.fail("expected 503");
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(503);
        }
    }

    @Test
    void adminRequiresTokenAndDbLatencyRejected() {
        try {
            http().get().uri("/admin/faults").retrieve().toBodilessEntity();
            org.junit.jupiter.api.Assertions.fail("expected 401");
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
        try {
            fault("{\"dbLatencyMs\":100}");
            org.junit.jupiter.api.Assertions.fail("expected 400");
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Test
    void stubErrorFromPaymentBecomes502() {
        stubStatus = 500;
        assertThat(order()).isEqualTo(502);
    }
}
