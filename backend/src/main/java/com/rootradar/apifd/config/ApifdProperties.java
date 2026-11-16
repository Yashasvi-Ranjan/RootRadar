package com.rootradar.apifd.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding of the {@code apifd.*} section of application.yml. Defaults here match application.yml so the
 * classes are usable in plain unit tests. Each agent may extend ONLY its own nested record.
 */
@ConfigurationProperties(prefix = "apifd")
public record ApifdProperties(
        String frontendOrigin,
        boolean demoMode,
        Monitoring monitoring,
        Telemetry telemetry,
        Detection detection,
        Incident incident,
        Realtime realtime,
        Retention retention) {

    public ApifdProperties {
        if (frontendOrigin == null) frontendOrigin = "http://localhost:5173";
        if (monitoring == null) monitoring = new Monitoring(null, null, 8, 1000, 10, 2000, 1_048_576);
        if (telemetry == null) telemetry = new Telemetry(10, 3000, 60);
        if (detection == null) detection = new Detection(true, 10, 30, 10);
        if (incident == null) incident = new Incident(2, 3, 300, 10);
        if (realtime == null) realtime = new Realtime(0, 15, 100);
        if (retention == null) retention = new Retention(false, 7, "0 30 3 * * *");
    }

    /** Owner: Agent 2. */
    public record Monitoring(List<String> allowedHosts, List<String> allowedSchemes, int maxConcurrentChecks,
                             long schedulerTickMs, int defaultIntervalSeconds, int defaultTimeoutMs,
                             long maxResponseBytes) {
        public Monitoring {
            if (allowedHosts == null || allowedHosts.isEmpty()) allowedHosts = List.of("localhost", "127.0.0.1");
            if (allowedSchemes == null || allowedSchemes.isEmpty()) allowedSchemes = List.of("http", "https");
        }
    }

    /** Owner: Agent 2. */
    public record Telemetry(int pollIntervalSeconds, int pollTimeoutMs, int pollMaxWindows) {}

    /** Owner: Agent 5 (pipeline) / Agent 3 (rules). */
    public record Detection(boolean enabled, int intervalSeconds, int lookbackMinutes, int windowSeconds) {}

    /** Owner: Agent 5. */
    public record Incident(int confirmEvaluations, int recoveryEvaluations, int reopenWindowSeconds,
                           int noDataResolveMinutes) {}

    /** Owner: Agent 5. */
    public record Realtime(long sseTimeoutMs, int heartbeatSeconds, int maxEmitters) {}

    /** Documented only in v1. */
    public record Retention(boolean enabled, int rawDays, String cron) {}
}
