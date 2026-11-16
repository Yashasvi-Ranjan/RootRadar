package com.rootradar.apifd.telemetry;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.common.Constants;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure aggregation helpers. Counts are summed, averages weighted by request count, percentiles are the
 * count-weighted mean of per-window percentiles (approximate), max is the maximum.
 */
final class MetricMath {

    private MetricMath() {}

    /** null = raw windows; otherwise a multiple of the window length in [windowSeconds, 3600]. */
    static void validateBucket(Integer bucketSeconds, int windowSeconds) {
        if (bucketSeconds == null) return;
        int w = Math.max(1, windowSeconds);
        if (bucketSeconds < w || bucketSeconds > 3600 || bucketSeconds % w != 0) {
            throw new ApiException.BadRequest("BAD_REQUEST",
                    "bucketSeconds must be a multiple of " + w + " between " + w + " and 3600");
        }
    }

    /** Points must be sorted ascending by windowStart. Output buckets are aligned to epoch multiples. */
    static List<MetricPoint> rebucket(List<MetricPoint> points, Integer bucketSeconds) {
        if (bucketSeconds == null) return points;
        Map<Long, List<MetricPoint>> groups = new LinkedHashMap<>();
        for (MetricPoint p : points) {
            long key = Math.floorDiv(p.windowStart().getEpochSecond(), (long) bucketSeconds) * bucketSeconds;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        List<MetricPoint> out = new ArrayList<>(groups.size());
        groups.forEach((k, g) -> out.add(merge(Instant.ofEpochSecond(k), Instant.ofEpochSecond(k + bucketSeconds), g)));
        return out;
    }

    /** Merges points that share a windowStart (several callers measuring the same node). Input sorted by windowStart. */
    static List<MetricPoint> mergeByWindow(List<MetricPoint> points) {
        Map<Instant, List<MetricPoint>> groups = new LinkedHashMap<>();
        for (MetricPoint p : points) groups.computeIfAbsent(p.windowStart(), k -> new ArrayList<>()).add(p);
        List<MetricPoint> out = new ArrayList<>(groups.size());
        groups.forEach((k, g) -> out.add(g.size() == 1 ? g.get(0) : merge(k, g.get(0).windowEnd(), g)));
        return out;
    }

    static MetricPoint merge(Instant start, Instant end, List<MetricPoint> group) {
        long count = 0, errors = 0;
        double avg = 0, p50 = 0, p95 = 0, p99 = 0, max = 0;
        for (MetricPoint p : group) {
            if (p.requestCount() <= 0) continue;
            count += p.requestCount();
            errors += p.errorCount();
            avg += p.avgMs() * p.requestCount();
            p50 += p.p50Ms() * p.requestCount();
            p95 += p.p95Ms() * p.requestCount();
            p99 += p.p99Ms() * p.requestCount();
            max = Math.max(max, p.maxMs());
        }
        if (count == 0) return new MetricPoint(start, end, 0, 0, 0, 0, 0, 0, 0);
        return new MetricPoint(start, end, count, errors, avg / count, p50 / count, p95 / count, p99 / count, max);
    }

    static int windowSecondsOr(int configured) {
        return configured > 0 ? configured : Constants.DEFAULT_WINDOW_SECONDS;
    }
}
