package com.rootradar.apifd.telemetry;

/**
 * Validates and persists a parsed {@link TelemetryReport}. Implemented by Agent 2 (bean {@code TelemetryIngestServiceImpl}).
 * Idempotent: windows already stored (unique service+endpoint+method+windowStart) are skipped, so re-polling
 * with overlap is safe. Rejects reports whose {@code service} differs from the polled node's name, windows with
 * negative values, errorCount &gt; count, or more than apifd.telemetry.poll-max-windows windows (extras dropped, logged).
 * After persisting, publishes {@code telemetry.updated}.
 */
public interface TelemetryIngestService {

    /** @return number of NEW windows stored. */
    int ingest(long serviceId, TelemetryReport report);
}
