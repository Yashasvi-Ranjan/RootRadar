package com.rootradar.apifd.monitoring;

/**
 * Kind of node in the dependency graph.
 * HTTP_SERVICE: has base URL + health endpoint, is health-checked and (if telemetryEndpoint set) polled.
 * DEPENDENCY_ONLY: non-HTTP component (the PostgreSQL "database"). Never health-checked or polled; its metrics are
 * the {@code dependencies[]} measurements that its callers report (see service_dependencies.measurement_name).
 */
public enum NodeType { HTTP_SERVICE, DEPENDENCY_ONLY }
