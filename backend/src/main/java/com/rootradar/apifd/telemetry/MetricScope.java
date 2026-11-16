package com.rootradar.apifd.telemetry;

/**
 * What a metric series describes.
 * SERVICE_INBOUND: service-wide rollup of requests the service SERVED (telemetry endpoint='*').
 * ENDPOINT: one inbound endpoint (method + path template).
 * DEPENDENCY_CALL: calls MEASURED BY A CALLER towards a downstream node. subjectService = the downstream node
 *   (e.g. "database"), observedBy = the caller (e.g. "payment-service").
 * HEALTH_CHECK: synthetic health checks (only used by anomalies, not by metric series).
 */
public enum MetricScope { SERVICE_INBOUND, ENDPOINT, DEPENDENCY_CALL, HEALTH_CHECK }
