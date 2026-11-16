package com.rootradar.apifd.monitoring;

import java.time.Instant;

/**
 * A registered graph node (row of {@code services}). Also the JSON shape of service resources.
 * For DEPENDENCY_ONLY nodes baseUrl/healthEndpoint/telemetryEndpoint are null.
 */
public record MonitoredService(
        long id,
        String name,
        String displayName,
        NodeType nodeType,
        String baseUrl,
        String healthEndpoint,
        String telemetryEndpoint,
        int checkIntervalSeconds,
        int timeoutMs,
        boolean enabled,
        boolean demo,
        String description,
        Instant createdAt,
        Instant updatedAt) {}
