package com.rootradar.apifd.anomaly;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;

/**
 * Persisted anomaly as returned by the API: all {@link AnomalyEvent} fields (flattened in JSON) plus persistence data.
 * status OPEN while the same fingerprint keeps firing, CLEARED once it stopped. Agent 5 persists/serves it.
 */
public record AnomalyEventView(
        long id,
        Long incidentId,
        String status,
        Instant lastSeenAt,
        Instant clearedAt,
        int occurrenceCount,
        @JsonUnwrapped AnomalyEvent event) {}
