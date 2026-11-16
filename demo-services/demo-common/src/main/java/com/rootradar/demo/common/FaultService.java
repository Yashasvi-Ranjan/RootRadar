package com.rootradar.demo.common;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Holds the current fault configuration (thread-safe, optional auto-expiry) and applies it to requests. */
public class FaultService {
    private final String service;
    private final Clock clock;
    private final boolean dbFaultsSupported;
    private final DoubleSupplier random;

    private volatile FaultConfig config = FaultConfig.none();
    private volatile Instant updatedAt;
    private volatile Instant expiresAt;

    public FaultService(String service, Clock clock, boolean dbFaultsSupported) {
        this(service, clock, dbFaultsSupported, () -> ThreadLocalRandom.current().nextDouble());
    }

    public FaultService(String service, Clock clock, boolean dbFaultsSupported, DoubleSupplier random) {
        this.service = service;
        this.clock = clock;
        this.dbFaultsSupported = dbFaultsSupported;
        this.random = random;
        this.updatedAt = clock.instant();
    }

    /** Effective config; an expired fault is cleared lazily. */
    public FaultConfig current() {
        Instant exp = expiresAt;
        if (exp != null && !clock.instant().isBefore(exp)) {
            clearInternal();
        }
        return config;
    }

    public FaultState state() {
        FaultConfig c = current();
        return FaultState.of(c, service, updatedAt, expiresAt);
    }

    public synchronized FaultState replace(FaultConfig c) {
        validate(c);
        Instant now = clock.instant();
        this.config = c;
        this.updatedAt = now;
        this.expiresAt = c.durationSeconds() == null ? null : now.plusSeconds(c.durationSeconds());
        return FaultState.of(c, service, updatedAt, expiresAt);
    }

    public synchronized FaultState clear() {
        clearInternal();
        return state();
    }

    private synchronized void clearInternal() {
        this.config = FaultConfig.none();
        this.expiresAt = null;
        this.updatedAt = clock.instant();
    }

    private void validate(FaultConfig c) {
        check(c.latencyMs() >= 0 && c.latencyMs() <= 30000, "latencyMs must be between 0 and 30000");
        check(c.latencyJitterMs() >= 0 && c.latencyJitterMs() <= 10000, "latencyJitterMs must be between 0 and 10000");
        check(c.errorRate() >= 0.0 && c.errorRate() <= 1.0, "errorRate must be between 0 and 1");
        check(c.errorStatus() >= 500 && c.errorStatus() <= 599, "errorStatus must be between 500 and 599");
        check(c.dbLatencyMs() >= 0 && c.dbLatencyMs() <= 30000, "dbLatencyMs must be between 0 and 30000");
        check(c.dbLatencyMs() == 0 || dbFaultsSupported, "dbLatencyMs is only supported by payment-service");
        check(c.durationSeconds() == null || (c.durationSeconds() >= 1 && c.durationSeconds() <= 3600),
                "durationSeconds must be between 1 and 3600");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new ApiException.Validation(msg);
        }
    }

    /** Milliseconds of artificial latency to sleep for this request (0 when none). */
    public long latencyToInjectMs(FaultConfig c) {
        long ms = c.latencyMs();
        if (c.latencyJitterMs() > 0) {
            ms += (long) (random.getAsDouble() * (c.latencyJitterMs() + 1));
        }
        return ms;
    }

    /** True when this request should be failed with the configured error status. */
    public boolean shouldFail(FaultConfig c) {
        return c.errorRate() > 0 && random.getAsDouble() < c.errorRate();
    }

    public boolean healthDown() {
        FaultConfig c = current();
        return c.unavailable() && c.affectsHealth();
    }

    /** Milliseconds the database call must sleep via pg_sleep (payment-service only). */
    public int dbLatencyMs() {
        return current().dbLatencyMs();
    }
}
