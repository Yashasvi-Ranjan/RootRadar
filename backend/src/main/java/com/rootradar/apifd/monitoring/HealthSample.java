package com.rootradar.apifd.monitoring;

import java.time.Instant;

/** Minimal health observation handed to the pure anomaly detector (consecutive-failure rule). */
public record HealthSample(String serviceName, Instant checkedAt, boolean up) {}
