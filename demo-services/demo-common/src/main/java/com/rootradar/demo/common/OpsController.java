package com.rootradar.demo.common;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /health and GET /internal/telemetry (every service). Not subject to fault latency/errors. */
@RestController
public class OpsController {
    private final TelemetryRegistry telemetry;
    private final FaultService faults;
    private final Clock clock;
    private final String service;

    public OpsController(TelemetryRegistry telemetry, FaultService faults, Clock clock,
                         @Value("${spring.application.name}") String service) {
        this.telemetry = telemetry;
        this.faults = faults;
        this.clock = clock;
        this.service = service;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean down = faults.healthDown();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", down ? "DOWN" : "UP");
        m.put("service", service);
        m.put("demo", true);
        m.put("time", clock.instant().toString());
        return ResponseEntity.status(down ? 503 : 200).body(m);
    }

    @GetMapping("/internal/telemetry")
    public TelemetryReport telemetry(@RequestParam(name = "since", required = false) String since) {
        Instant s = null;
        if (since != null && !since.isBlank()) {
            try {
                s = Instant.parse(since);
            } catch (DateTimeParseException e) {
                throw new ApiException.BadRequest("since must be an ISO-8601 instant");
            }
        }
        return telemetry.report(s);
    }
}
