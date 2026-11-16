package com.rootradar.apifd.common;

/** Cross-package constants. Add only genuinely shared values. */
public final class Constants {
    private Constants() {}

    /** Telemetry window length produced by the demo services (seconds). Mirrors apifd.detection.window-seconds. */
    public static final int DEFAULT_WINDOW_SECONDS = 10;
    /** Telemetry rows with this endpoint/method are the service-wide rollup. */
    public static final String ALL = "*";
    /** Header carrying the demo fault-injection admin token. */
    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    public static final String API_PREFIX = "/api";
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;
    /** Maximum span accepted for from/to history queries. */
    public static final java.time.Duration MAX_QUERY_RANGE = java.time.Duration.ofDays(31);
}
