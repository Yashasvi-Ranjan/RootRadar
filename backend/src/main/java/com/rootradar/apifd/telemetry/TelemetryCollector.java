package com.rootradar.apifd.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.monitoring.HealthOutcome;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.monitoring.SafeHttpClient;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import com.rootradar.apifd.telemetry.persistence.PollResultEntity;
import com.rootradar.apifd.telemetry.persistence.PollResultRepository;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Polls {@code GET <telemetryEndpoint>?since=<newest stored windowEnd>} of one node, parses the
 * {@link TelemetryReport}, hands it to {@link TelemetryIngestService} and records a row in telemetry_poll_results
 * (collector reachability is tracked separately from health checks). Never throws for network/parse problems.
 */
@Component
public class TelemetryCollector {

    private static final Logger log = LoggerFactory.getLogger(TelemetryCollector.class);

    /** Result of one poll. */
    public record PollOutcome(long serviceId, boolean success, int windowsReceived, int windowsStored, String error) {}

    private final ServiceRegistry registry;
    private final SafeHttpClient http;
    private final TelemetryStore store;
    private final TelemetryIngestService ingest;
    private final PollResultRepository polls;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int pollTimeoutMs;
    private final Map<Long, Boolean> lastOk = new ConcurrentHashMap<>();

    public TelemetryCollector(ServiceRegistry registry, SafeHttpClient http, TelemetryStore store,
                              TelemetryIngestService ingest, PollResultRepository polls, ObjectMapper mapper,
                              Clock clock, ApifdProperties props) {
        this.registry = registry;
        this.http = http;
        this.store = store;
        this.ingest = ingest;
        this.polls = polls;
        this.mapper = mapper;
        this.clock = clock;
        this.pollTimeoutMs = props.telemetry().pollTimeoutMs();
    }

    public PollOutcome pollNow(long serviceId) {
        MonitoredService s = registry.findById(serviceId)
                .orElseThrow(() -> new ApiException.NotFound("Service " + serviceId + " not found"));
        if (s.nodeType() != NodeType.HTTP_SERVICE || s.telemetryEndpoint() == null) {
            throw new ApiException.BadRequest("BAD_REQUEST", "Node '" + s.name() + "' has no telemetry endpoint");
        }
        Instant polledAt = clock.instant();
        long t0 = System.nanoTime();
        int received = 0, stored = 0;
        String error = null;
        try {
            Instant since = store.latestWindowEnd(serviceId);
            String ep = s.telemetryEndpoint();
            String path = since == null ? ep : ep + (ep.contains("?") ? "&" : "?") + "since="
                    + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8);
            SafeHttpClient.Response r = http.get(s.baseUrl(), path, pollTimeoutMs);
            if (r.status() < 200 || r.status() >= 300) {
                error = "HTTP " + r.status();
            } else {
                TelemetryReport report = mapper.readValue(r.body(), TelemetryReport.class);
                received = report.windows() == null ? 0 : report.windows().size();
                stored = ingest.ingest(serviceId, report);
            }
        } catch (SafeHttpClient.FetchException e) {
            error = e.outcome() + ": " + e.getMessage();
        } catch (IOException e) {
            error = "Unparseable telemetry JSON";
        } catch (ApiException e) {
            error = "Rejected: " + e.getMessage();
        } catch (RuntimeException e) {
            error = "Unexpected error: " + e.getClass().getSimpleName();
            log.warn("Telemetry poll of {} failed unexpectedly", s.name(), e);
        }
        int durationMs = (int) ((System.nanoTime() - t0) / 1_000_000);
        boolean ok = error == null;
        try {
            polls.save(new PollResultEntity(serviceId, polledAt, ok, durationMs, received, stored, error));
        } catch (RuntimeException e) {
            log.warn("Could not persist poll result for {}: {}", s.name(), e.toString());
        }
        Boolean prev = lastOk.put(serviceId, ok);
        if (!ok && !Boolean.FALSE.equals(prev)) {
            log.warn("Telemetry poll of {} failing: {}", s.name(), error);
        } else if (ok && Boolean.FALSE.equals(prev)) {
            log.info("Telemetry poll of {} recovered", s.name());
        }
        return new PollOutcome(serviceId, ok, received, stored, error);
    }
}
