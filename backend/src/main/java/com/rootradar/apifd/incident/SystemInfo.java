package com.rootradar.apifd.incident;

import java.time.Instant;

/** GET /api/system/info body. */
public record SystemInfo(boolean demoMode, String version, Instant serverTime, int windowSeconds,
                         int detectionIntervalSeconds, Instant lastDetectionRunAt) {}
