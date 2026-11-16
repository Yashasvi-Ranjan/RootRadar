package com.rootradar.apifd.telemetry;

import java.util.Objects;

/**
 * Identity of a metric series.
 *
 * @param serviceName    subject node (for DEPENDENCY_CALL: the downstream node, e.g. "database")
 * @param scope          see {@link MetricScope}
 * @param method         HTTP method for ENDPOINT scope, else null
 * @param endpoint       path template for ENDPOINT scope, else null
 * @param dependencyName caller-side measurement name for DEPENDENCY_CALL, else null
 * @param observedBy     caller node name for DEPENDENCY_CALL, else null
 */
public record SeriesKey(String serviceName, MetricScope scope, String method, String endpoint,
                        String dependencyName, String observedBy) {

    public SeriesKey {
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(scope);
    }

    public static SeriesKey inbound(String service) {
        return new SeriesKey(service, MetricScope.SERVICE_INBOUND, null, null, null, null);
    }

    public static SeriesKey endpoint(String service, String method, String endpoint) {
        return new SeriesKey(service, MetricScope.ENDPOINT, method, endpoint, null, null);
    }

    public static SeriesKey dependency(String downstreamNode, String caller, String dependencyName) {
        return new SeriesKey(downstreamNode, MetricScope.DEPENDENCY_CALL, null, null, dependencyName, caller);
    }

    /** Stable string used inside anomaly fingerprints: scope|service|method endpoint|dependency|observedBy. */
    public String asFingerprintPart() {
        return scope + "|" + serviceName + "|" + (method == null ? "" : method) + " " + (endpoint == null ? "" : endpoint)
                + "|" + (dependencyName == null ? "" : dependencyName) + "|" + (observedBy == null ? "" : observedBy);
    }
}
