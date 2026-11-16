package com.rootradar.apifd.anomaly;

/**
 * Metric a rule evaluates. Thresholds are in the metric's unit: LATENCY_* milliseconds, ERROR_RATE fraction 0..1,
 * HEALTH_CONSECUTIVE_FAILURES number of consecutive failed health checks.
 */
public enum RuleMetric { LATENCY_P95_MS, LATENCY_AVG_MS, ERROR_RATE, HEALTH_CONSECUTIVE_FAILURES }
