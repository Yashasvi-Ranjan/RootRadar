-- V1: core schema. OWNED BY AGENT 1. Do not edit; if a change is unavoidable ask the orchestrator for a new Vn migration.
-- All timestamps are timestamptz (UTC). Enumerations are varchar + CHECK (no PG enums: easier JPA mapping).

-- ===== Monitoring ==========================================================
CREATE TABLE services (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(100)  NOT NULL UNIQUE,          -- machine name, e.g. payment-service
    display_name            VARCHAR(150)  NOT NULL,
    node_type               VARCHAR(20)   NOT NULL,                 -- HTTP_SERVICE | DEPENDENCY_ONLY
    base_url                VARCHAR(500),                           -- NULL for DEPENDENCY_ONLY
    health_endpoint         VARCHAR(300),                           -- path, e.g. /health ; NULL for DEPENDENCY_ONLY
    telemetry_endpoint      VARCHAR(300),                           -- path, e.g. /internal/telemetry ; NULL = not polled
    check_interval_seconds  INTEGER       NOT NULL DEFAULT 10,
    timeout_ms              INTEGER       NOT NULL DEFAULT 2000,
    enabled                 BOOLEAN       NOT NULL DEFAULT TRUE,
    demo                    BOOLEAN       NOT NULL DEFAULT FALSE,   -- TRUE = fault-injectable demo component
    description             TEXT,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_services_node_type CHECK (node_type IN ('HTTP_SERVICE','DEPENDENCY_ONLY')),
    CONSTRAINT ck_services_http_fields CHECK (
        node_type = 'DEPENDENCY_ONLY' OR (base_url IS NOT NULL AND health_endpoint IS NOT NULL)),
    CONSTRAINT ck_services_interval CHECK (check_interval_seconds BETWEEN 1 AND 3600),
    CONSTRAINT ck_services_timeout CHECK (timeout_ms BETWEEN 100 AND 60000)
);

-- Directed edge: from_service CALLS / DEPENDS ON to_service.
CREATE TABLE service_dependencies (
    id               BIGSERIAL PRIMARY KEY,
    from_service_id  BIGINT       NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    to_service_id    BIGINT       NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    -- Name the CALLER uses for this dependency in its /internal/telemetry "dependencies[].name".
    -- This is how a DEPENDENCY_ONLY node (database) obtains its metrics.
    measurement_name VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_dependency_edge UNIQUE (from_service_id, to_service_id),
    CONSTRAINT uq_dependency_measurement UNIQUE (from_service_id, measurement_name),
    CONSTRAINT ck_dependency_no_self_loop CHECK (from_service_id <> to_service_id)
);
CREATE INDEX ix_service_dependencies_to ON service_dependencies (to_service_id);

CREATE TABLE health_check_results (
    id               BIGSERIAL PRIMARY KEY,
    service_id       BIGINT       NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    checked_at       TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(10)  NOT NULL,       -- UP | DOWN
    outcome          VARCHAR(30)  NOT NULL,       -- SUCCESS | HTTP_ERROR | TIMEOUT | CONNECTION_FAILED | BLOCKED | INVALID_RESPONSE
    http_status      INTEGER,
    response_time_ms INTEGER,
    error_message    VARCHAR(500),
    CONSTRAINT ck_health_status CHECK (status IN ('UP','DOWN')),
    CONSTRAINT ck_health_outcome CHECK (outcome IN
        ('SUCCESS','HTTP_ERROR','TIMEOUT','CONNECTION_FAILED','BLOCKED','INVALID_RESPONSE'))
);
CREATE INDEX ix_health_service_time ON health_check_results (service_id, checked_at DESC);
CREATE INDEX ix_health_time ON health_check_results (checked_at);

-- ===== Telemetry ===========================================================
-- One row per (service, endpoint, window). endpoint='*' and method='*' is the service-wide rollup
-- computed by the service itself from raw samples (exact percentiles).
CREATE TABLE telemetry_snapshots (
    id             BIGSERIAL PRIMARY KEY,
    service_id     BIGINT           NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    collected_at   TIMESTAMPTZ      NOT NULL,
    window_start   TIMESTAMPTZ      NOT NULL,
    window_end     TIMESTAMPTZ      NOT NULL,
    window_seconds INTEGER          NOT NULL,
    method         VARCHAR(10)      NOT NULL,
    endpoint       VARCHAR(300)     NOT NULL,
    request_count  INTEGER          NOT NULL,
    error_count    INTEGER          NOT NULL,
    avg_ms         DOUBLE PRECISION NOT NULL,
    p50_ms         DOUBLE PRECISION NOT NULL,
    p95_ms         DOUBLE PRECISION NOT NULL,
    p99_ms         DOUBLE PRECISION NOT NULL,
    max_ms         DOUBLE PRECISION NOT NULL,
    CONSTRAINT uq_telemetry_window UNIQUE (service_id, endpoint, method, window_start),
    CONSTRAINT ck_telemetry_counts CHECK (request_count >= 0 AND error_count >= 0 AND error_count <= request_count)
);
CREATE INDEX ix_telemetry_service_time ON telemetry_snapshots (service_id, window_start DESC);
CREATE INDEX ix_telemetry_time ON telemetry_snapshots (window_start);

-- One row per (caller, dependency, window): measured INSIDE the caller.
CREATE TABLE dependency_measurements (
    id                BIGSERIAL PRIMARY KEY,
    caller_service_id BIGINT           NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    dependency_name   VARCHAR(100)     NOT NULL,     -- matches service_dependencies.measurement_name
    dependency_type   VARCHAR(20)      NOT NULL,     -- HTTP | JDBC
    collected_at      TIMESTAMPTZ      NOT NULL,
    window_start      TIMESTAMPTZ      NOT NULL,
    window_end        TIMESTAMPTZ      NOT NULL,
    window_seconds    INTEGER          NOT NULL,
    request_count     INTEGER          NOT NULL,
    error_count       INTEGER          NOT NULL,     -- includes timeout_count
    timeout_count     INTEGER          NOT NULL DEFAULT 0,
    avg_ms            DOUBLE PRECISION NOT NULL,
    p50_ms            DOUBLE PRECISION NOT NULL,
    p95_ms            DOUBLE PRECISION NOT NULL,
    p99_ms            DOUBLE PRECISION NOT NULL,
    max_ms            DOUBLE PRECISION NOT NULL,
    CONSTRAINT uq_dependency_window UNIQUE (caller_service_id, dependency_name, window_start),
    CONSTRAINT ck_dep_counts CHECK (request_count >= 0 AND error_count >= 0 AND error_count <= request_count)
);
CREATE INDEX ix_depmeas_time ON dependency_measurements (window_start);
CREATE INDEX ix_depmeas_name_time ON dependency_measurements (dependency_name, window_start DESC);

-- Outcome of each telemetry poll (diagnoses "collector could not reach service" separately from health checks).
CREATE TABLE telemetry_poll_results (
    id               BIGSERIAL PRIMARY KEY,
    service_id       BIGINT       NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    polled_at        TIMESTAMPTZ  NOT NULL,
    success          BOOLEAN      NOT NULL,
    duration_ms      INTEGER,
    windows_received INTEGER      NOT NULL DEFAULT 0,
    windows_stored   INTEGER      NOT NULL DEFAULT 0,
    error_message    VARCHAR(500)
);
CREATE INDEX ix_poll_service_time ON telemetry_poll_results (service_id, polled_at DESC);

-- ===== Detection rules =====================================================
CREATE TABLE detection_rules (
    id                          BIGSERIAL PRIMARY KEY,
    rule_key                    VARCHAR(100)     NOT NULL,   -- human id, e.g. latency-p95-default
    metric                      VARCHAR(40)      NOT NULL,   -- LATENCY_P95_MS | LATENCY_AVG_MS | ERROR_RATE | HEALTH_CONSECUTIVE_FAILURES
    service_id                  BIGINT REFERENCES services(id) ON DELETE CASCADE,  -- NULL = global default
    enabled                     BOOLEAN          NOT NULL DEFAULT TRUE,
    warning_threshold           DOUBLE PRECISION,
    critical_threshold          DOUBLE PRECISION,
    baseline_ratio              DOUBLE PRECISION,            -- observed/baseline median ratio that counts as deviation
    z_score_threshold           DOUBLE PRECISION,            -- robust (median/MAD) z-score
    min_requests                INTEGER          NOT NULL DEFAULT 3,
    consecutive_windows         INTEGER          NOT NULL DEFAULT 3,  -- breaches needed for WARNING
    critical_consecutive_windows INTEGER         NOT NULL DEFAULT 2,  -- breaches of the critical threshold needed for CRITICAL
    baseline_windows            INTEGER          NOT NULL DEFAULT 60,
    min_baseline_windows        INTEGER          NOT NULL DEFAULT 12,
    recovery_windows            INTEGER          NOT NULL DEFAULT 3,
    created_at                  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_rule_metric CHECK (metric IN
        ('LATENCY_P95_MS','LATENCY_AVG_MS','ERROR_RATE','HEALTH_CONSECUTIVE_FAILURES')),
    CONSTRAINT ck_rule_positive CHECK (min_requests >= 0 AND consecutive_windows >= 1
        AND critical_consecutive_windows >= 1 AND baseline_windows >= 1 AND min_baseline_windows >= 1
        AND recovery_windows >= 1)
);
CREATE UNIQUE INDEX uq_rule_key ON detection_rules (rule_key);
CREATE UNIQUE INDEX uq_rule_metric_service ON detection_rules (metric, COALESCE(service_id, 0));

-- ===== Incidents ===========================================================
CREATE TABLE incidents (
    id                     BIGSERIAL PRIMARY KEY,
    title                  VARCHAR(300) NOT NULL,
    status                 VARCHAR(15)  NOT NULL,           -- DETECTED | ACTIVE | RECOVERING | RESOLVED
    severity               VARCHAR(10)  NOT NULL,           -- WARNING | CRITICAL
    dedup_key              VARCHAR(300) NOT NULL,           -- fingerprint of the opening anomaly
    affected_endpoint      VARCHAR(300),                    -- e.g. "POST /api/checkout" (user-facing entry point) or NULL
    root_cause_service_id  BIGINT REFERENCES services(id),  -- latest suspected root cause (denormalised from newest diagnosis)
    root_cause_score       INTEGER,
    root_cause_summary     TEXT,
    detected_at            TIMESTAMPTZ  NOT NULL,           -- first abnormal window start / first detection
    activated_at           TIMESTAMPTZ,
    recovering_at          TIMESTAMPTZ,
    resolved_at            TIMESTAMPTZ,
    resolution_reason      VARCHAR(30),                     -- RECOVERED | NO_DATA
    last_anomaly_at        TIMESTAMPTZ,
    last_evaluated_at      TIMESTAMPTZ,
    reopen_count           INTEGER      NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                BIGINT       NOT NULL DEFAULT 0, -- JPA optimistic lock
    CONSTRAINT ck_incident_status CHECK (status IN ('DETECTED','ACTIVE','RECOVERING','RESOLVED')),
    CONSTRAINT ck_incident_severity CHECK (severity IN ('WARNING','CRITICAL')),
    CONSTRAINT ck_incident_score CHECK (root_cause_score IS NULL OR root_cause_score BETWEEN 0 AND 100)
);
-- Database-level guarantee: at most one open incident per dedup_key.
CREATE UNIQUE INDEX uq_incident_open_dedup ON incidents (dedup_key) WHERE status <> 'RESOLVED';
CREATE INDEX ix_incidents_status_time ON incidents (status, detected_at DESC);
CREATE INDEX ix_incidents_detected ON incidents (detected_at DESC);
CREATE INDEX ix_incidents_root_cause ON incidents (root_cause_service_id, detected_at DESC);

-- Services involved in an incident (impacted set).
CREATE TABLE incident_services (
    incident_id       BIGINT      NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    service_id        BIGINT      NOT NULL REFERENCES services(id),
    first_abnormal_at TIMESTAMPTZ,
    added_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (incident_id, service_id)
);
CREATE INDEX ix_incident_services_service ON incident_services (service_id);

CREATE TABLE anomaly_events (
    id                      BIGSERIAL PRIMARY KEY,
    incident_id             BIGINT REFERENCES incidents(id) ON DELETE SET NULL,
    fingerprint             VARCHAR(300)     NOT NULL,   -- type|subject|scope|endpoint|dependency
    anomaly_type            VARCHAR(30)      NOT NULL,   -- LATENCY | ERROR_RATE | HEALTH_CHECK_FAILURE
    severity                VARCHAR(10)      NOT NULL,   -- WARNING | CRITICAL
    status                  VARCHAR(10)      NOT NULL DEFAULT 'OPEN',  -- OPEN | CLEARED
    subject_service_id      BIGINT           NOT NULL REFERENCES services(id),
    observed_by_service_id  BIGINT REFERENCES services(id),            -- caller, for DEPENDENCY_CALL scope
    scope                   VARCHAR(20)      NOT NULL,   -- SERVICE_INBOUND | ENDPOINT | DEPENDENCY_CALL | HEALTH_CHECK
    endpoint_method         VARCHAR(10),
    endpoint                VARCHAR(300),
    dependency_name         VARCHAR(100),
    detection_method        VARCHAR(30)      NOT NULL,   -- STATIC_THRESHOLD | BASELINE_RATIO | ROBUST_Z_SCORE | CONSECUTIVE_FAILURES
    rule_key                VARCHAR(100)     NOT NULL,
    detected_at             TIMESTAMPTZ      NOT NULL,   -- evaluation time that first emitted this event
    first_breach_at         TIMESTAMPTZ      NOT NULL,   -- start of earliest breaching window in the streak
    window_start            TIMESTAMPTZ      NOT NULL,
    window_end              TIMESTAMPTZ      NOT NULL,
    observed_value          DOUBLE PRECISION NOT NULL,
    baseline_value          DOUBLE PRECISION,
    threshold_value         DOUBLE PRECISION,
    deviation_score         DOUBLE PRECISION,
    consecutive_breaches    INTEGER          NOT NULL,
    sample_count            INTEGER          NOT NULL,
    baseline_sample_count   INTEGER          NOT NULL DEFAULT 0,
    critical                BOOLEAN          NOT NULL DEFAULT FALSE,
    description             TEXT             NOT NULL,
    details                 JSONB,
    last_seen_at            TIMESTAMPTZ      NOT NULL,   -- updated while the same anomaly is still firing
    cleared_at              TIMESTAMPTZ,
    occurrence_count        INTEGER          NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_anomaly_type CHECK (anomaly_type IN ('LATENCY','ERROR_RATE','HEALTH_CHECK_FAILURE')),
    CONSTRAINT ck_anomaly_status CHECK (status IN ('OPEN','CLEARED')),
    CONSTRAINT ck_anomaly_severity CHECK (severity IN ('WARNING','CRITICAL'))
);
CREATE INDEX ix_anomaly_detected ON anomaly_events (detected_at DESC);
CREATE INDEX ix_anomaly_subject_time ON anomaly_events (subject_service_id, detected_at DESC);
CREATE INDEX ix_anomaly_incident ON anomaly_events (incident_id);
CREATE INDEX ix_anomaly_fingerprint_open ON anomaly_events (fingerprint) WHERE status = 'OPEN';

CREATE TABLE incident_timeline_events (
    id           BIGSERIAL PRIMARY KEY,
    incident_id  BIGINT       NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    event_type   VARCHAR(30)  NOT NULL,   -- INCIDENT_DETECTED | ANOMALY_DETECTED | ANOMALY_CLEARED | STATUS_CHANGED | SEVERITY_CHANGED | DIAGNOSIS_UPDATED | RECOVERY_STARTED | RECOVERY_ABORTED | REOPENED | RESOLVED
    from_status  VARCHAR(15),
    to_status    VARCHAR(15),
    service_id   BIGINT REFERENCES services(id),
    title        VARCHAR(300) NOT NULL,
    description  TEXT,
    details      JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_timeline_incident_time ON incident_timeline_events (incident_id, occurred_at, id);

-- ===== Diagnoses (RCA output, one row per analysis run; history kept) ======
CREATE TABLE incident_diagnoses (
    id                    BIGSERIAL PRIMARY KEY,
    incident_id           BIGINT       NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    analyzed_at           TIMESTAMPTZ  NOT NULL,
    model_version         VARCHAR(30)  NOT NULL,      -- scoring model version string, see docs/RCA_SCORING_MODEL.md
    probable_cause_service_id BIGINT REFERENCES services(id),
    top_score             INTEGER,
    conclusive            BOOLEAN      NOT NULL,
    summary               TEXT         NOT NULL,
    caveats               JSONB,                      -- array of strings
    input_snapshot        JSONB,                      -- DiagnosisInput as analysed, for reproducibility/audit
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_diag_score CHECK (top_score IS NULL OR top_score BETWEEN 0 AND 100)
);
CREATE INDEX ix_diag_incident_time ON incident_diagnoses (incident_id, analyzed_at DESC);

CREATE TABLE incident_diagnosis_findings (
    id                BIGSERIAL PRIMARY KEY,
    diagnosis_id      BIGINT       NOT NULL REFERENCES incident_diagnoses(id) ON DELETE CASCADE,
    service_id        BIGINT       NOT NULL REFERENCES services(id),
    rank              INTEGER      NOT NULL,           -- 1 = highest score
    classification    VARCHAR(25)  NOT NULL,           -- PROBABLE_CAUSE | CONTRIBUTING_FACTOR | AFFECTED | INSUFFICIENT_EVIDENCE
    score             INTEGER      NOT NULL,           -- 0..100 explainable ranking score
    first_abnormal_at TIMESTAMPTZ,
    evidence          JSONB,                           -- array of strings
    CONSTRAINT ck_finding_class CHECK (classification IN
        ('PROBABLE_CAUSE','CONTRIBUTING_FACTOR','AFFECTED','INSUFFICIENT_EVIDENCE')),
    CONSTRAINT ck_finding_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT uq_finding_service UNIQUE (diagnosis_id, service_id)
);
CREATE INDEX ix_findings_service ON incident_diagnosis_findings (service_id);

CREATE TABLE incident_diagnosis_factors (
    id          BIGSERIAL PRIMARY KEY,
    finding_id  BIGINT       NOT NULL REFERENCES incident_diagnosis_findings(id) ON DELETE CASCADE,
    seq         INTEGER      NOT NULL,                 -- display order
    rule_key    VARCHAR(60)  NOT NULL,
    label       VARCHAR(200) NOT NULL,                 -- e.g. "Latency anomaly"
    points      INTEGER      NOT NULL,                 -- may be negative (penalty)
    max_points  INTEGER      NOT NULL,
    fired       BOOLEAN      NOT NULL,
    detail      TEXT
);
CREATE INDEX ix_factors_finding ON incident_diagnosis_factors (finding_id, seq);
