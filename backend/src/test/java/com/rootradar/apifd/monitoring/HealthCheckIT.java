package com.rootradar.apifd.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.config.MonitoringExecutorConfig;
import com.rootradar.apifd.realtime.LiveEvent;
import com.rootradar.apifd.realtime.LiveEventType;
import com.rootradar.apifd.realtime.LiveEvents;
import com.rootradar.testsupport.mon.MonitoringIT;
import com.rootradar.testsupport.mon.TestHttpServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;

/** Health checks against in-process JDK HTTP servers; results are persisted in the real PostgreSQL test DB. */
@SuppressWarnings("resource")
class HealthCheckIT extends MonitoringIT {

    @Autowired HealthCheckRunner runner;
    @Autowired HealthQueryService health;
    @Autowired Clock clock;
    @Autowired ApifdProperties props;
    @Autowired @Qualifier(MonitoringExecutorConfig.BEAN_NAME) Executor executor;

    @Test
    void successfulCheckIsUpPersistedAndPublished() throws Exception {
        try (TestHttpServer srv = new TestHttpServer().respond("/health", 200, "{\"status\":\"UP\"}").start()) {
            MonitoredService s = registerHttp("ok", srv.baseUrl(), 2000);
            HealthCheckResult r = runner.runNow(s.id());

            assertEquals(HealthStatus.UP, r.status());
            assertEquals(HealthOutcome.SUCCESS, r.outcome());
            assertEquals(200, r.httpStatus());
            assertNotNull(r.responseTimeMs());
            assertTrue(r.responseTimeMs() < 1500);
            assertNull(r.errorMessage());
            assertEquals(s.name(), r.serviceName());

            // persisted in PostgreSQL (read back through a separate query path)
            Integer rows = jdbc.queryForObject(
                    "select count(*) from health_check_results where service_id = ? and status = 'UP' and outcome = 'SUCCESS'",
                    Integer.class, s.id());
            assertEquals(1, rows);
            assertEquals(r.id(), health.latest(s.id()).orElseThrow().id());
            assertEquals(r.id(), health.latestForAll().get(s.id()).id());

            // health.updated published with the same result
            LiveEvent ev = publisher.events.stream().filter(e -> e.type() == LiveEventType.HEALTH_UPDATED)
                    .findFirst().orElseThrow();
            LiveEvents.HealthUpdated payload = (LiveEvents.HealthUpdated) ev.data();
            assertEquals(s.id(), payload.serviceId());
            assertEquals(r.id(), payload.result().id());
        }
    }

    @Test
    void non2xxIsDownHttpError() throws Exception {
        try (TestHttpServer srv = new TestHttpServer().respond("/health", 503, "{\"status\":\"DOWN\"}").start()) {
            MonitoredService s = registerHttp("http503", srv.baseUrl(), 2000);
            HealthCheckResult r = runner.runNow(s.id());
            assertEquals(HealthStatus.DOWN, r.status());
            assertEquals(HealthOutcome.HTTP_ERROR, r.outcome());
            assertEquals(503, r.httpStatus());
        }
    }

    @Test
    void unreachableServiceIsDownConnectionFailedAndDoesNotThrow() throws Exception {
        MonitoredService s = registerHttp("refused", "http://127.0.0.1:" + TestHttpServer.closedPort(), 1000);
        HealthCheckResult r = runner.runNow(s.id());
        assertEquals(HealthStatus.DOWN, r.status());
        assertEquals(HealthOutcome.CONNECTION_FAILED, r.outcome());
        assertNull(r.httpStatus());
        assertNotNull(r.errorMessage());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from health_check_results where service_id = ? and outcome = 'CONNECTION_FAILED'",
                Integer.class, s.id()));
    }

    @Test
    void slowServiceTimesOutWithinConfiguredTimeout() throws Exception {
        try (TestHttpServer srv = new TestHttpServer().handle("/health", ex -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                TestHttpServer.send(ex, 200, "{}".getBytes());
            } catch (Exception ignored) {
                // client already gave up
            }
        }).start()) {
            MonitoredService s = registerHttp("slow", srv.baseUrl(), 300);
            long t0 = System.nanoTime();
            HealthCheckResult r = runner.runNow(s.id());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertEquals(HealthStatus.DOWN, r.status());
            assertEquals(HealthOutcome.TIMEOUT, r.outcome());
            assertTrue(elapsedMs < 1500, "check must give up near the 300 ms timeout, took " + elapsedMs);
            assertTrue(r.responseTimeMs() >= 250, "recorded elapsed time should be about the timeout");
        }
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        try (TestHttpServer target = new TestHttpServer().respond("/health", 200, "{}").start();
             TestHttpServer srv = new TestHttpServer().handle("/health", ex -> {
                 ex.getResponseHeaders().add("Location", target.baseUrl() + "/health");
                 ex.sendResponseHeaders(302, -1);
                 ex.close();
             }).start()) {
            MonitoredService s = registerHttp("redirect", srv.baseUrl(), 2000);
            HealthCheckResult r = runner.runNow(s.id());
            assertEquals(HealthOutcome.HTTP_ERROR, r.outcome());
            assertEquals(302, r.httpStatus());
        }
    }

    @Test
    void oversizedBodyIsInvalidResponse() throws Exception {
        byte[] big = new byte[(int) props.monitoring().maxResponseBytes() + 4096];
        java.util.Arrays.fill(big, (byte) 'x');
        try (TestHttpServer srv = new TestHttpServer().handle("/health", ex -> TestHttpServer.send(ex, 200, big)).start()) {
            MonitoredService s = registerHttp("big", srv.baseUrl(), 3000);
            HealthCheckResult r = runner.runNow(s.id());
            assertEquals(HealthStatus.DOWN, r.status());
            assertEquals(HealthOutcome.INVALID_RESPONSE, r.outcome());
        }
    }

    @Test
    void ssrfRejectedAtRegistration() {
        for (String url : List.of("http://evil.example.com", "http://169.254.169.254", "file:///etc/passwd",
                "http://user:pw@localhost:8080", "ftp://localhost")) {
            ApiException.BadRequest e = assertThrows(ApiException.BadRequest.class, () -> registry.register(
                    new RegisterServiceRequest(uniqueName("ssrf"), null, NodeType.HTTP_SERVICE, url, "/health", null,
                            10, 1000, true, null)), url);
            assertEquals("URL_NOT_ALLOWED", e.getCode());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from services where name like 'mon-t-ssrf-%'", Integer.class));
    }

    @Test
    void ssrfRejectedOnUpdate() throws Exception {
        try (TestHttpServer srv = new TestHttpServer().respond("/health", 200, "{}").start()) {
            MonitoredService s = registerHttp("ssrfupd", srv.baseUrl(), 1000);
            ApiException.BadRequest e = assertThrows(ApiException.BadRequest.class, () -> registry.update(s.id(),
                    new UpdateServiceRequest(null, "http://evil.example.com", null, null, null, null, null, null)));
            assertEquals("URL_NOT_ALLOWED", e.getCode());
            assertEquals(srv.baseUrl(), registry.findById(s.id()).orElseThrow().baseUrl());
        }
    }

    @Test
    void urlTamperedInDatabaseIsBlockedBeforeTheCall() {
        // Defence in depth: even if a bad URL got into the table, the runner re-validates before every call.
        MonitoredService s = registerHttp("tampered", "http://127.0.0.1:" + 9, 1000);
        jdbc.update("update services set base_url = 'http://evil.example.com' where id = ?", s.id());
        HealthCheckResult r = runner.runNow(s.id());
        assertEquals(HealthStatus.DOWN, r.status());
        assertEquals(HealthOutcome.BLOCKED, r.outcome());
        assertNull(r.responseTimeMs());
    }

    @Test
    void dependencyOnlyNodeCannotBeChecked() {
        MonitoredService db = registerDependencyOnly("db");
        ApiException.BadRequest e = assertThrows(ApiException.BadRequest.class, () -> runner.runNow(db.id()));
        assertEquals("BAD_REQUEST", e.getCode());
        assertThrows(ApiException.NotFound.class, () -> runner.runNow(-42));
        assertTrue(health.latest(db.id()).isEmpty());
    }

    @Test
    void slowServiceDoesNotBlockOtherServices() throws Exception {
        try (TestHttpServer slow = new TestHttpServer().handle("/health", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                TestHttpServer.send(ex, 200, "{}".getBytes());
            } catch (Exception ignored) {
                // ignore
            }
        }).start(); TestHttpServer fast = new TestHttpServer().respond("/health", 200, "{}").start()) {
            MonitoredService slowSvc = registerHttp("hol-slow", slow.baseUrl(), 3000);
            MonitoredService fastSvc = registerHttp("hol-fast", fast.baseUrl(), 3000);
            HealthCheckScheduler scheduler = new HealthCheckScheduler(registry, runner, executor, clock, props);
            Instant t0 = Instant.now();
            scheduler.tick(); // must return immediately: tick never does I/O itself
            assertTrue(Duration.between(t0, Instant.now()).toMillis() < 500, "tick must not block on slow service");

            awaitRows(fastSvc.id(), 1, 1000);
            assertEquals(0, count(slowSvc.id()), "slow service is still in flight while the fast one already finished");
            awaitRows(slowSvc.id(), 1, 5000);

            // second tick right away: neither service is due again (10 s interval) -> no extra checks
            scheduler.tick();
            Thread.sleep(300);
            assertEquals(1, count(fastSvc.id()));
            assertEquals(1, count(slowSvc.id()));
        }
    }

    @Test
    void historyAndAvailabilityReflectStoredChecks() throws Exception {
        try (TestHttpServer srv = new TestHttpServer().respond("/health", 200, "{}").start()) {
            MonitoredService s = registerHttp("avail", srv.baseUrl(), 1000);
            Instant base = Instant.parse("2026-11-01T10:00:00Z");
            // 6 checks in two 5-minute buckets: bucket0 = 3 UP, bucket1 = 1 UP + 2 DOWN
            int[][] rows = {{0, 1, 10}, {60, 1, 20}, {120, 1, 30}, {300, 1, 40}, {330, 0, 0}, {360, 0, 0}};
            for (int[] r : rows) {
                jdbc.update("insert into health_check_results (service_id, checked_at, status, outcome, http_status, response_time_ms)"
                                + " values (?, ?, ?, ?, ?, ?)", s.id(), java.sql.Timestamp.from(base.plusSeconds(r[0])),
                        r[1] == 1 ? "UP" : "DOWN", r[1] == 1 ? "SUCCESS" : "CONNECTION_FAILED",
                        r[1] == 1 ? 200 : null, r[1] == 1 ? r[2] : null);
            }
            Instant from = base, to = base.plusSeconds(600);
            AvailabilityReport rep = health.availability(s.id(), from, to, 5);
            assertEquals(6, rep.totalChecks());
            assertEquals(2, rep.failedChecks());
            assertEquals(100.0 * 4 / 6, rep.uptimePercent(), 1e-9);
            assertEquals(25.0, rep.avgResponseTimeMs(), 1e-9);
            assertEquals(2, rep.buckets().size());
            assertEquals(100.0, rep.buckets().get(0).uptimePercent());
            assertEquals(3, rep.buckets().get(0).totalChecks());
            assertEquals(100.0 / 3, rep.buckets().get(1).uptimePercent(), 1e-9);

            var page = health.history(s.id(), from, to, PageRequest.of(0, 4));
            assertEquals(6, page.getTotalElements());
            assertEquals(4, page.getContent().size());
            assertEquals(base.plusSeconds(360), page.getContent().get(0).checkedAt(), "newest first");
            // time-range filter is half-open [from, to)
            assertEquals(3, health.history(s.id(), base, base.plusSeconds(300), PageRequest.of(0, 50)).getTotalElements());

            // detector samples: only HTTP nodes, oldest first
            List<HealthSample> samples = health.recentSamples(base.plusSeconds(300));
            List<HealthSample> mine = samples.stream().filter(x -> x.serviceName().equals(s.name())).toList();
            assertEquals(3, mine.size());
            assertTrue(mine.get(0).up());
            assertFalse(mine.get(2).up());

            assertThrows(ApiException.BadRequest.class, () -> health.availability(s.id(), to, from, 5));
            assertThrows(ApiException.BadRequest.class, () -> health.availability(s.id(), from, to, 0));
            assertThrows(ApiException.NotFound.class, () -> health.availability(-1, from, to, 5));
        }
    }

    private int count(long serviceId) {
        return jdbc.queryForObject("select count(*) from health_check_results where service_id = ?", Integer.class,
                serviceId);
    }

    private void awaitRows(long serviceId, int expected, long maxMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMs);
        while (System.nanoTime() < deadline) {
            if (count(serviceId) >= expected) return;
            Thread.sleep(25);
        }
        assertEquals(expected, count(serviceId), "timed out waiting for check rows");
    }
}
