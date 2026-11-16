package com.rootradar.apifd.incident;

import com.rootradar.apifd.common.ServiceRef;
import com.rootradar.apifd.common.Severity;
import java.time.Instant;
import java.util.List;

/**
 * Incident list item and base of the detail view.
 * @param rootCause         latest suspected root-cause service (null until the first diagnosis)
 * @param rootCauseScore    0..100 explainable ranking score of rootCause (null until diagnosed)
 * @param rootCauseSummary  summary text of the latest diagnosis (null until diagnosed)
 * @param affectedEndpoint  user-facing entry point, e.g. "POST /api/checkout", null if unknown
 * @param impactedServices  services in the incident's impacted set
 * @param durationSeconds   (resolvedAt or now) - detectedAt
 */
public record IncidentSummary(
        long id,
        String title,
        IncidentStatus status,
        Severity severity,
        String affectedEndpoint,
        ServiceRef rootCause,
        Integer rootCauseScore,
        String rootCauseSummary,
        List<ServiceRef> impactedServices,
        Instant detectedAt,
        Instant activatedAt,
        Instant recoveringAt,
        Instant resolvedAt,
        String resolutionReason,
        int reopenCount,
        long durationSeconds) {}
