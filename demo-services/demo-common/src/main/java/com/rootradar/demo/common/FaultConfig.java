package com.rootradar.demo.common;

/** Request body of PUT /admin/faults (docs/API_CONTRACTS.md 7.4). Omitted fields take their defaults. */
public record FaultConfig(int latencyMs, int latencyJitterMs, double errorRate, int errorStatus, boolean unavailable,
                          boolean affectsHealth, int dbLatencyMs, Integer durationSeconds) {

    public FaultConfig {
        if (errorStatus == 0) {
            errorStatus = 500;
        }
    }

    public static FaultConfig none() {
        return new FaultConfig(0, 0, 0.0, 500, false, false, 0, null);
    }

    /** True when at least one fault is configured. */
    public boolean active() {
        return latencyMs > 0 || latencyJitterMs > 0 || errorRate > 0 || unavailable || dbLatencyMs > 0;
    }
}
