package com.rootradar.apifd.telemetry;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import com.rootradar.apifd.realtime.EventPublisher;
import com.rootradar.apifd.realtime.LiveEvent;
import com.rootradar.apifd.realtime.LiveEventType;
import com.rootradar.apifd.realtime.LiveEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Validates and persists a {@link TelemetryReport}. Idempotent (unique window keys + ON CONFLICT DO NOTHING).
 *
 * <p>Whole-report rejection (ApiException.BadRequest): unknown node, DEPENDENCY_ONLY node, report.service differing
 * from the node name. Per-window skip (logged, rest stored): missing/unordered timestamps, negative or non-finite
 * numbers, errorCount &gt; count. Bounds: only the newest {@code poll-max-windows} windows are considered; at most
 * 100 endpoints and 50 dependencies per window are stored.
 */
@Service
public class TelemetryIngestServiceImpl implements TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestServiceImpl.class);
    static final int MAX_ENDPOINTS_PER_WINDOW = 100;
    static final int MAX_DEPENDENCIES_PER_WINDOW = 50;

    private final ServiceRegistry registry;
    private final TelemetryStore store;
    private final TransactionTemplate tx;
    private final EventPublisher events;
    private final Clock clock;
    private final int maxWindows;

    public TelemetryIngestServiceImpl(ServiceRegistry registry, TelemetryStore store,
                                      PlatformTransactionManager txm, EventPublisher events, Clock clock,
                                      ApifdProperties props) {
        this.registry = registry;
        this.store = store;
        this.tx = new TransactionTemplate(txm);
        this.events = events;
        this.clock = clock;
        this.maxWindows = Math.max(1, props.telemetry().pollMaxWindows());
    }

    @Override
    public int ingest(long serviceId, TelemetryReport report) {
        MonitoredService s = registry.findById(serviceId)
                .orElseThrow(() -> new ApiException.NotFound("Service " + serviceId + " not found"));
        if (s.nodeType() != com.rootradar.apifd.monitoring.NodeType.HTTP_SERVICE) {
            throw new ApiException.BadRequest("BAD_REQUEST", "Node '" + s.name() + "' does not expose telemetry");
        }
        if (report == null || report.service() == null || !report.service().equals(s.name())) {
            throw new ApiException.BadRequest("BAD_REQUEST", "Telemetry report service '"
                    + (report == null ? null : report.service()) + "' does not match polled node '" + s.name() + "'");
        }
        List<TelemetryReport.Window> windows = report.windows() == null ? List.of() : report.windows();
        if (windows.size() > maxWindows) {
            log.warn("Telemetry report of {} has {} windows; keeping newest {}", s.name(), windows.size(), maxWindows);
            windows = windows.stream().filter(w -> w != null && w.windowEnd() != null)
                    .sorted(Comparator.comparing(TelemetryReport.Window::windowEnd).reversed())
                    .limit(maxWindows).toList();
        }
        Instant now = clock.instant();
        List<TelemetryStore.SnapshotRow> snaps = new ArrayList<>();
        List<Integer> overallIdx = new ArrayList<>();
        List<Instant> overallEnds = new ArrayList<>();
        List<TelemetryStore.DependencyRow> deps = new ArrayList<>();
        for (TelemetryReport.Window w : windows) {
            int secs = validWindowSeconds(w, report.windowSeconds());
            if (secs <= 0) {
                log.warn("Skipping invalid telemetry window from {}", s.name());
                continue;
            }
            TelemetryReport.Overall o = w.overall();
            overallIdx.add(snaps.size());
            overallEnds.add(w.windowEnd());
            snaps.add(new TelemetryStore.SnapshotRow(serviceId, now, w.windowStart(), w.windowEnd(), secs, "*", "*",
                    o.count(), o.errorCount(), o.avgMs(), o.p50Ms(), o.p95Ms(), o.p99Ms(), o.maxMs()));
            int e = 0;
            for (TelemetryReport.EndpointStats ep : nz(w.endpoints())) {
                if (e >= MAX_ENDPOINTS_PER_WINDOW) {
                    log.warn("Endpoint cardinality cap hit for {}", s.name());
                    break;
                }
                if (ep == null || blank(ep.method()) || blank(ep.endpoint()) || ep.method().length() > 10
                        || ep.endpoint().length() > 300 || "*".equals(ep.endpoint())
                        || !valid(ep.count(), ep.errorCount(), ep.avgMs(), ep.p50Ms(), ep.p95Ms(), ep.p99Ms(), ep.maxMs())) {
                    continue;
                }
                e++;
                snaps.add(new TelemetryStore.SnapshotRow(serviceId, now, w.windowStart(), w.windowEnd(), secs,
                        ep.method().toUpperCase(), ep.endpoint(), ep.count(), ep.errorCount(), ep.avgMs(), ep.p50Ms(),
                        ep.p95Ms(), ep.p99Ms(), ep.maxMs()));
            }
            int d = 0;
            for (TelemetryReport.DependencyStats dp : nz(w.dependencies())) {
                if (d >= MAX_DEPENDENCIES_PER_WINDOW) break;
                if (dp == null || blank(dp.name()) || dp.name().length() > 100
                        || !valid(dp.count(), dp.errorCount(), dp.avgMs(), dp.p50Ms(), dp.p95Ms(), dp.p99Ms(), dp.maxMs())
                        || dp.timeoutCount() < 0 || dp.timeoutCount() > dp.errorCount()) {
                    continue;
                }
                d++;
                String type = blank(dp.type()) ? "HTTP" : dp.type().length() > 20 ? dp.type().substring(0, 20) : dp.type();
                deps.add(new TelemetryStore.DependencyRow(serviceId, dp.name(), type, now, w.windowStart(),
                        w.windowEnd(), secs, dp.count(), dp.errorCount(), dp.timeoutCount(), dp.avgMs(), dp.p50Ms(),
                        dp.p95Ms(), dp.p99Ms(), dp.maxMs()));
            }
        }
        if (snaps.isEmpty()) return 0;

        List<Instant> newEnds = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            int[] res = store.insertSnapshots(snaps);
            for (int k = 0; k < overallIdx.size(); k++) {
                int r = res[overallIdx.get(k)];
                if (r > 0 || r == java.sql.Statement.SUCCESS_NO_INFO) newEnds.add(overallEnds.get(k));
            }
            store.insertDependencies(deps);
        });
        int stored = newEnds.size();
        if (stored > 0) {
            Instant latest = newEnds.stream().max(Comparator.naturalOrder()).orElse(null);
            try {
                events.publish(new LiveEvent(LiveEventType.TELEMETRY_UPDATED, now,
                        new LiveEvents.TelemetryUpdated(serviceId, s.name(), stored, latest)));
            } catch (RuntimeException ex) {
                log.debug("Publishing telemetry.updated failed: {}", ex.toString());
            }
        }
        return stored;
    }

    private static int validWindowSeconds(TelemetryReport.Window w, int reported) {
        if (w == null || w.windowStart() == null || w.windowEnd() == null || w.overall() == null
                || !w.windowEnd().isAfter(w.windowStart())) {
            return -1;
        }
        TelemetryReport.Overall o = w.overall();
        if (!valid(o.count(), o.errorCount(), o.avgMs(), o.p50Ms(), o.p95Ms(), o.p99Ms(), o.maxMs())) return -1;
        long secs = reported > 0 ? reported : w.windowEnd().getEpochSecond() - w.windowStart().getEpochSecond();
        return secs > 0 && secs <= 3600 ? (int) secs : -1;
    }

    private static boolean valid(long count, long errors, double avg, double p50, double p95, double p99, double max) {
        if (count < 0 || errors < 0 || errors > count) return false;
        for (double v : new double[]{avg, p50, p95, p99, max}) {
            if (!Double.isFinite(v) || v < 0) return false;
        }
        return true;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
