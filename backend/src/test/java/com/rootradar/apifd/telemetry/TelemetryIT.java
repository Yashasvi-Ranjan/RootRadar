package com.rootradar.apifd.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.graph.AddEdgeRequest;
import com.rootradar.apifd.graph.DependencyGraphService;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.realtime.LiveEventType;
import com.rootradar.apifd.realtime.LiveEvents;
import com.rootradar.testsupport.mon.MonitoringIT;
import com.rootradar.testsupport.mon.TestHttpServer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Telemetry ingest (idempotency, validation), collector polling, metric queries; real PostgreSQL. */
class TelemetryIT extends MonitoringIT {

    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");

    @Autowired TelemetryIngestService ingest;
    @Autowired TelemetryCollector collector;
    @Autowired MetricsQueryService metrics;
    @Autowired DependencyGraphService graph;
    @Autowired ObjectMapper mapper;

    // ---- report builders -------------------------------------------------------------------------------------

    private static TelemetryReport.Window window(int idx, long count, long err, double avg, double p95,
                                                 List<TelemetryReport.EndpointStats> eps,
                                                 List<TelemetryReport.DependencyStats> deps) {
        Instant s = T0.plusSeconds(idx * 10L);
        return new TelemetryReport.Window(s, s.plusSeconds(10),
                new TelemetryReport.Overall(count, err, avg, avg, p95, p95, p95 * 1.2), eps, deps);
    }

    private static TelemetryReport.EndpointStats ep(String m, String path, long count, long err, double avg) {
        return new TelemetryReport.EndpointStats(m, path, count, err, avg, avg, avg * 2, avg * 2, avg * 3);
    }

    private static TelemetryReport.DependencyStats dep(String name, String type, long count, long err, double avg) {
        return new TelemetryReport.DependencyStats(name, type, count, err, 0, avg, avg, avg * 2, avg * 2, avg * 3);
    }

    private static TelemetryReport report(String service, TelemetryReport.Window... ws) {
        return new TelemetryReport(1, service, T0.plusSeconds(600), T0.minusSeconds(600), 10, List.of(ws));
    }

    private int rows(String table, String col, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + col + " = ?", Integer.class, id);
    }

    // ---- ingest ---------------------------------------------------------------------------------------------

    @Test
    void ingestStoresWindowsEndpointsAndDependenciesAndIsIdempotent() {
        MonitoredService pay = registerHttp("pay", "http://127.0.0.1:1", 1000);
        TelemetryReport r = report(pay.name(),
                window(0, 10, 0, 40, 80, List.of(ep("POST", "/api/payments", 10, 0, 40)),
                        List.of(dep("database", "JDBC", 10, 0, 4))),
                window(1, 12, 1, 45, 90, List.of(ep("POST", "/api/payments", 12, 1, 45)),
                        List.of(dep("database", "JDBC", 12, 0, 5))));

        assertEquals(2, ingest.ingest(pay.id(), r));
        assertEquals(4, rows("telemetry_snapshots", "service_id", pay.id()), "2 windows x (overall + 1 endpoint)");
        assertEquals(2, rows("dependency_measurements", "caller_service_id", pay.id()));
        assertEquals(1, publisher.events.stream().filter(e -> e.type() == LiveEventType.TELEMETRY_UPDATED).count());
        LiveEvents.TelemetryUpdated ev = (LiveEvents.TelemetryUpdated) publisher.events.get(0).data();
        assertEquals(2, ev.windowsStored());
        assertEquals(T0.plusSeconds(20), ev.latestWindowEnd());

        // identical re-ingest: nothing new, nothing duplicated, no event
        publisher.events.clear();
        assertEquals(0, ingest.ingest(pay.id(), r));
        assertEquals(4, rows("telemetry_snapshots", "service_id", pay.id()));
        assertEquals(2, rows("dependency_measurements", "caller_service_id", pay.id()));
        assertTrue(publisher.events.isEmpty());

        // overlapping re-poll with one extra window: only the new one is stored
        TelemetryReport overlap = report(pay.name(), r.windows().get(1),
                window(2, 8, 0, 41, 70, List.of(ep("POST", "/api/payments", 8, 0, 41)),
                        List.of(dep("database", "JDBC", 8, 0, 4))));
        assertEquals(1, ingest.ingest(pay.id(), overlap));
        assertEquals(6, rows("telemetry_snapshots", "service_id", pay.id()));
        assertEquals(3, rows("dependency_measurements", "caller_service_id", pay.id()));
    }

    @Test
    void ingestRejectsReportForDifferentService() {
        MonitoredService a = registerHttp("a", "http://127.0.0.1:1", 1000);
        TelemetryReport wrong = report("some-other-service", window(0, 1, 0, 1, 1, List.of(), List.of()));
        ApiException.BadRequest e = assertThrows(ApiException.BadRequest.class, () -> ingest.ingest(a.id(), wrong));
        assertTrue(e.getMessage().contains("does not match"));
        assertEquals(0, rows("telemetry_snapshots", "service_id", a.id()));
        assertThrows(ApiException.NotFound.class, () -> ingest.ingest(-5, wrong));
        MonitoredService db = registerDependencyOnly("db");
        assertThrows(ApiException.BadRequest.class, () -> ingest.ingest(db.id(), report(db.name())));
    }

    @Test
    void invalidWindowsAreSkippedValidOnesStored() {
        MonitoredService a = registerHttp("inv", "http://127.0.0.1:1", 1000);
        Instant s = T0.plusSeconds(30);
        TelemetryReport.Window negative = new TelemetryReport.Window(T0, T0.plusSeconds(10),
                new TelemetryReport.Overall(5, 0, -1, 1, 1, 1, 1), List.of(), List.of());
        TelemetryReport.Window errMoreThanCount = new TelemetryReport.Window(T0.plusSeconds(10), T0.plusSeconds(20),
                new TelemetryReport.Overall(2, 3, 1, 1, 1, 1, 1), List.of(), List.of());
        TelemetryReport.Window nan = new TelemetryReport.Window(T0.plusSeconds(20), T0.plusSeconds(30),
                new TelemetryReport.Overall(2, 0, Double.NaN, 1, 1, 1, 1), List.of(), List.of());
        TelemetryReport.Window noOverall = new TelemetryReport.Window(s, s.plusSeconds(10), null, List.of(), List.of());
        TelemetryReport.Window good = window(5, 4, 1, 10, 20,
                List.of(ep("GET", "/x", 4, 9, 1) /* errors > count: entry skipped */, ep("GET", "/y", 4, 1, 10)),
                List.of(dep("database", "JDBC", 4, 0, 1)));
        int stored = ingest.ingest(a.id(), report(a.name(), negative, errMoreThanCount, nan, noOverall, good));
        assertEquals(1, stored);
        assertEquals(2, rows("telemetry_snapshots", "service_id", a.id()), "good overall + valid endpoint /y only");
    }

    @Test
    void windowCountIsBoundedKeepingNewest() {
        MonitoredService a = registerHttp("cap", "http://127.0.0.1:1", 1000);
        List<TelemetryReport.Window> ws = new ArrayList<>();
        for (int i = 0; i < 70; i++) ws.add(window(i, 1, 0, 1, 1, List.of(), List.of()));
        TelemetryReport big = new TelemetryReport(1, a.name(), T0, T0, 10, ws);
        assertEquals(60, ingest.ingest(a.id(), big)); // apifd.telemetry.poll-max-windows = 60
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from telemetry_snapshots where service_id = ? and window_start < ?", Integer.class,
                a.id(), java.sql.Timestamp.from(T0.plusSeconds(100))), "oldest 10 windows were dropped");
    }

    // ---- collector ------------------------------------------------------------------------------------------

    @Test
    void collectorPollsWithSinceCursorStoresWindowsAndRecordsPollResults() throws Exception {
        List<String> queries = new CopyOnWriteArrayList<>();
        AtomicReference<TelemetryReport> current = new AtomicReference<>();
        try (TestHttpServer srv = new TestHttpServer().handle("/internal/telemetry", ex -> {
            queries.add(String.valueOf(ex.getRequestURI().getRawQuery()));
            TestHttpServer.send(ex, 200, mapper.writeValueAsBytes(current.get()));
        }).start()) {
            MonitoredService s = registerHttp("poll", srv.baseUrl(), 1000);
            current.set(report(s.name(), window(0, 5, 0, 30, 50, List.of(ep("GET", "/a", 5, 0, 30)), List.of()),
                    window(1, 6, 0, 31, 51, List.of(), List.of(dep("database", "JDBC", 6, 0, 2)))));

            TelemetryCollector.PollOutcome o1 = collector.pollNow(s.id());
            assertTrue(o1.success(), o1.error());
            assertEquals(2, o1.windowsReceived());
            assertEquals(2, o1.windowsStored());
            assertEquals("null", queries.get(0), "first poll has no since cursor");

            // service now returns an overlapping report (windows 1 and 2): since must equal newest stored windowEnd
            current.set(report(s.name(), window(1, 6, 0, 31, 51, List.of(), List.of(dep("database", "JDBC", 6, 0, 2))),
                    window(2, 7, 0, 32, 52, List.of(), List.of())));
            TelemetryCollector.PollOutcome o2 = collector.pollNow(s.id());
            assertTrue(o2.success());
            assertEquals(2, o2.windowsReceived());
            assertEquals(1, o2.windowsStored(), "idempotent: overlap not stored twice");
            String since = URLDecoder.decode(queries.get(1), StandardCharsets.UTF_8);
            assertEquals("since=" + T0.plusSeconds(20), since);

            assertEquals(2, rows("telemetry_poll_results", "service_id", s.id()));
            assertEquals(2, jdbc.queryForObject(
                    "select count(*) from telemetry_poll_results where service_id = ? and success", Integer.class, s.id()));
            assertEquals(3, jdbc.queryForObject(
                    "select count(*) from telemetry_snapshots where service_id = ? and endpoint = '*'", Integer.class, s.id()));
        }
    }

    @Test
    void collectorRecordsFailuresWithoutThrowing() throws Exception {
        // unreachable
        MonitoredService down = registerHttp("polldown", "http://127.0.0.1:" + TestHttpServer.closedPort(), 500);
        TelemetryCollector.PollOutcome o = collector.pollNow(down.id());
        assertFalse(o.success());
        assertNotNull(o.error());
        // HTTP 500, garbage JSON, wrong service name
        try (TestHttpServer srv = new TestHttpServer().respond("/internal/telemetry", 500, "boom").start()) {
            MonitoredService s500 = registerHttp("poll500", srv.baseUrl(), 1000);
            assertEquals("HTTP 500", collector.pollNow(s500.id()).error());
        }
        try (TestHttpServer srv = new TestHttpServer().respond("/internal/telemetry", 200, "not json").start()) {
            MonitoredService s = registerHttp("pollbad", srv.baseUrl(), 1000);
            assertEquals("Unparseable telemetry JSON", collector.pollNow(s.id()).error());
        }
        try (TestHttpServer srv = new TestHttpServer().handle("/internal/telemetry", ex ->
                TestHttpServer.send(ex, 200, mapper.writeValueAsBytes(report("impostor", window(0, 1, 0, 1, 1, List.of(), List.of()))))).start()) {
            MonitoredService s = registerHttp("pollwrong", srv.baseUrl(), 1000);
            TelemetryCollector.PollOutcome r = collector.pollNow(s.id());
            assertFalse(r.success());
            assertTrue(r.error().startsWith("Rejected"));
            assertEquals(0, rows("telemetry_snapshots", "service_id", s.id()));
        }
        // timeout
        try (TestHttpServer srv = new TestHttpServer().handle("/internal/telemetry", ex -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }).start()) {
            MonitoredService s = registerHttp("polltimeout", srv.baseUrl(), 1000);
            long t0 = System.nanoTime();
            TelemetryCollector.PollOutcome r = collector.pollNow(s.id());
            assertFalse(r.success());
            assertTrue(r.error().startsWith("TIMEOUT"), r.error());
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 3900, "poll timeout is apifd.telemetry.poll-timeout-ms");
        }
        assertEquals(1, rows("telemetry_poll_results", "service_id", down.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from telemetry_poll_results where success and service_id = ?",
                Integer.class, down.id()));
    }

    // ---- queries --------------------------------------------------------------------------------------------

    @Test
    void metricsQueriesFilterByRangeBucketAndEndpoint() {
        MonitoredService pay = registerHttp("q", "http://127.0.0.1:1", 1000);
        List<TelemetryReport.Window> ws = new ArrayList<>();
        for (int i = 0; i < 6; i++) { // 6 windows = 1 minute
            ws.add(window(i, 10, i == 5 ? 5 : 0, 20 + i * 10, 40 + i * 10,
                    List.of(ep("POST", "/api/payments", 6, 0, 10), ep("GET", "/api/payments/{id}", 4, 0, 5)),
                    List.of(dep("database", "JDBC", 10, 0, 3))));
        }
        ingest.ingest(pay.id(), new TelemetryReport(1, pay.name(), T0, T0, 10, ws));

        Instant from = T0, to = T0.plusSeconds(60);
        List<MetricPoint> raw = metrics.serviceSeries(pay.id(), from, to, null);
        assertEquals(6, raw.size());
        assertEquals(T0, raw.get(0).windowStart());
        assertEquals(0.5, raw.get(5).errorRate(), 1e-9);

        // half-open range: [T0+10, T0+30) -> windows 1,2
        assertEquals(2, metrics.serviceSeries(pay.id(), T0.plusSeconds(10), T0.plusSeconds(30), null).size());

        List<MetricPoint> b = metrics.serviceSeries(pay.id(), from, to, 30);
        assertEquals(2, b.size());
        assertEquals(30, b.get(0).requestCount());
        assertEquals((20 + 30 + 40) / 3.0, b.get(0).avgMs(), 1e-9);

        // endpoint series: method-specific, and any-method merge
        assertEquals(6, metrics.endpointSeries(pay.id(), "POST", "/api/payments", from, to, null).size());
        assertEquals(6, metrics.endpointSeries(pay.id(), null, "/api/payments/{id}", from, to, null).size());
        assertTrue(metrics.endpointSeries(pay.id(), "DELETE", "/api/payments", from, to, null).isEmpty());

        MetricsSummary sum = metrics.serviceSummary(pay.id(), from, to).orElseThrow();
        assertEquals(60, sum.requestCount());
        assertEquals(5, sum.errorCount());
        assertEquals(5.0 / 60, sum.errorRate(), 1e-9);
        assertEquals(MetricsSource.INBOUND_TELEMETRY, sum.source());
        assertTrue(sum.percentilesApproximate());
        assertTrue(metrics.serviceSummary(pay.id(), T0.plusSeconds(3600), T0.plusSeconds(3700)).isEmpty());

        List<EndpointSummary> eps = metrics.endpointSummaries(pay.id(), from, to);
        assertEquals(2, eps.size());
        assertEquals("/api/payments", eps.get(0).endpoint());
        assertEquals(36, eps.get(0).summary().requestCount());

        Map<String, MetricsSummary> deps = metrics.dependencySummaries(pay.id(), from, to);
        assertEquals(60, deps.get("database").requestCount());
        assertEquals(6, metrics.dependencySeries(pay.id(), "database", from, to, null).size());

        assertThrows(ApiException.BadRequest.class, () -> metrics.serviceSeries(pay.id(), to, from, null));
        assertThrows(ApiException.BadRequest.class, () -> metrics.serviceSeries(pay.id(), from, to, 15));
        assertThrows(ApiException.BadRequest.class, () -> metrics.serviceSeries(pay.id(), from, from.plusSeconds(40L * 86400), null));
        assertThrows(ApiException.NotFound.class, () -> metrics.serviceSeries(-9, from, to, null));
    }

    @Test
    void dependencyOnlyNodeMetricsComeFromCallerMeasurementsAndLoadAllSeriesCoversEverything() {
        MonitoredService pay = registerHttp("lp", "http://127.0.0.1:1", 1000);
        MonitoredService ord = registerHttp("lo", "http://127.0.0.1:1", 1000);
        MonitoredService db = registerDependencyOnly("ldb");
        graph.addEdge(new AddEdgeRequest(ord.id(), pay.id(), pay.name()));
        graph.addEdge(new AddEdgeRequest(pay.id(), db.id(), "database"));

        ingest.ingest(pay.id(), report(pay.name(),
                window(0, 10, 0, 40, 80, List.of(ep("POST", "/api/payments", 10, 0, 40)), List.of(dep("database", "JDBC", 10, 1, 4))),
                window(1, 10, 0, 40, 80, List.of(ep("POST", "/api/payments", 10, 0, 40)), List.of(dep("database", "JDBC", 20, 0, 6)))));
        ingest.ingest(ord.id(), report(ord.name(),
                window(0, 10, 0, 60, 120, List.of(ep("POST", "/api/orders", 10, 0, 60)), List.of(dep(pay.name(), "HTTP", 10, 0, 45)))));

        Instant from = T0, to = T0.plusSeconds(60);
        List<MetricPoint> dbSeries = metrics.serviceSeries(db.id(), from, to, null);
        assertEquals(2, dbSeries.size());
        assertEquals(10, dbSeries.get(0).requestCount());
        assertEquals(1, dbSeries.get(0).errorCount());
        assertEquals(6.0, dbSeries.get(1).avgMs());
        MetricsSummary dbSum = metrics.serviceSummary(db.id(), from, to).orElseThrow();
        assertEquals(MetricsSource.CALLER_MEASUREMENT, dbSum.source());
        assertEquals(30, dbSum.requestCount());
        assertEquals((10 * 4 + 20 * 6) / 30.0, dbSum.avgMs(), 1e-9);

        List<MetricSeries> all = metrics.loadAllSeries(from, to);
        assertTrue(all.stream().anyMatch(x -> x.key().equals(SeriesKey.inbound(pay.name()))));
        assertTrue(all.stream().anyMatch(x -> x.key().equals(SeriesKey.inbound(ord.name()))));
        assertTrue(all.stream().anyMatch(x -> x.key().equals(SeriesKey.inbound(db.name()))),
                "database inbound series synthesised from caller measurements");
        assertTrue(all.stream().anyMatch(x -> x.key().equals(SeriesKey.endpoint(pay.name(), "POST", "/api/payments"))));
        MetricSeries depCall = all.stream().filter(x -> x.key().equals(SeriesKey.dependency(db.name(), pay.name(), "database")))
                .findFirst().orElseThrow();
        assertEquals(2, depCall.points().size());
        assertTrue(all.stream().anyMatch(x -> x.key().equals(SeriesKey.dependency(pay.name(), ord.name(), pay.name()))));
        // ascending order, no duplicates
        for (MetricSeries ms : all) {
            for (int i = 1; i < ms.points().size(); i++) {
                assertTrue(ms.points().get(i).windowStart().isAfter(ms.points().get(i - 1).windowStart()));
            }
        }

        // disabled nodes are omitted
        jdbc.update("update services set enabled = false where id = ?", ord.id());
        assertFalse(metrics.loadAllSeries(from, to).stream().anyMatch(x -> x.key().serviceName().equals(ord.name())));
        // empty range window has no series
        assertTrue(metrics.loadAllSeries(T0.plusSeconds(7200), T0.plusSeconds(7260)).isEmpty());
        assertEquals(dbSeries.size(), metrics.serviceSeries(db.id(), from, to, null).size());
    }
}
