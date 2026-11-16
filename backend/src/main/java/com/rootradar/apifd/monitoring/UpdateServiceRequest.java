package com.rootradar.apifd.monitoring;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PUT /api/services/{id} body: partial update, null fields are left unchanged. name and nodeType are immutable. */
public record UpdateServiceRequest(
        @Size(max = 150) String displayName,
        @Size(max = 500) String baseUrl,
        @Size(max = 300) @Pattern(regexp = "^/.*$", message = "must start with '/'") String healthEndpoint,
        @Size(max = 300) @Pattern(regexp = "^/.*$", message = "must start with '/'") String telemetryEndpoint,
        @Min(1) @Max(3600) Integer checkIntervalSeconds,
        @Min(100) @Max(60000) Integer timeoutMs,
        Boolean enabled,
        @Size(max = 2000) String description) {}
