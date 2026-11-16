package com.rootradar.apifd.incident;

import com.rootradar.apifd.common.Severity;
import java.time.Instant;
import java.util.List;

/** Query filter for GET /api/incidents; every field optional (null/empty = no restriction). from/to apply to detectedAt. */
public record IncidentFilter(List<IncidentStatus> statuses, Severity severity, String service, Instant from, Instant to) {}
