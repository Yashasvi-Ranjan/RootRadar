package com.rootradar.apifd.monitoring;

/** UP = last health check returned 2xx; DOWN = failed; UNKNOWN = never checked / not applicable (DEPENDENCY_ONLY). */
public enum HealthStatus { UP, DOWN, UNKNOWN }
