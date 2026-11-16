package com.rootradar.demo.common;

import java.time.Instant;

/** Effective fault configuration plus bookkeeping; flat JSON as in the contract. */
public record FaultState(int latencyMs, int latencyJitterMs, double errorRate, int errorStatus, boolean unavailable,
                         boolean affectsHealth, int dbLatencyMs, Integer durationSeconds, boolean active,
                         String service, Instant updatedAt, Instant expiresAt) {

    static FaultState of(FaultConfig c, String service, Instant updatedAt, Instant expiresAt) {
        return new FaultState(c.latencyMs(), c.latencyJitterMs(), c.errorRate(), c.errorStatus(), c.unavailable(),
                c.affectsHealth(), c.dbLatencyMs(), c.durationSeconds(), c.active(), service, updatedAt, expiresAt);
    }
}
