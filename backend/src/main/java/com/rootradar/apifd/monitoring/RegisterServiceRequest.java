package com.rootradar.apifd.monitoring;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/services body. For nodeType HTTP_SERVICE baseUrl and healthEndpoint are required (checked by the
 * service layer, error code VALIDATION_FAILED); for DEPENDENCY_ONLY they must be null.
 * baseUrl must pass the SSRF policy (http/https + host in apifd.monitoring.allowed-hosts) else 400 URL_NOT_ALLOWED.
 */
public record RegisterServiceRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "lowercase letters, digits, '-'") String name,
        @Size(max = 150) String displayName,
        @NotNull NodeType nodeType,
        @Size(max = 500) String baseUrl,
        @Size(max = 300) @Pattern(regexp = "^/.*$", message = "must start with '/'") String healthEndpoint,
        @Size(max = 300) @Pattern(regexp = "^/.*$", message = "must start with '/'") String telemetryEndpoint,
        @Min(1) @Max(3600) Integer checkIntervalSeconds,
        @Min(100) @Max(60000) Integer timeoutMs,
        Boolean enabled,
        @Size(max = 2000) String description) {}
