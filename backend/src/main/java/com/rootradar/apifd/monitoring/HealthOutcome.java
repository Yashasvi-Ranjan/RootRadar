package com.rootradar.apifd.monitoring;

/** Why a health check ended the way it did. SUCCESS maps to UP; every other value maps to DOWN. */
public enum HealthOutcome { SUCCESS, HTTP_ERROR, TIMEOUT, CONNECTION_FAILED, BLOCKED, INVALID_RESPONSE }
