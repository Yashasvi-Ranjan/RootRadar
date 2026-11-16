package com.rootradar.apifd.incident;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.rootradar.apifd.rca.DiagnosisResult;

/** Persisted diagnosis: row ids plus the {@link DiagnosisResult} fields flattened into the JSON. */
public record DiagnosisView(long id, long incidentId, @JsonUnwrapped DiagnosisResult diagnosis) {}
