package com.rootradar.apifd.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rootradar.apifd.common.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricMathTest {

    private static final Instant T0 = Instant.parse("2026-11-16T04:00:00Z");

    private static MetricPoint p(int windowIdx, long count, long err, double avg, double p95, double max) {
        Instant s = T0.plusSeconds(windowIdx * 10L);
        return new MetricPoint(s, s.plusSeconds(10), count, err, avg, avg, p95, p95, max);
    }

    @Test
    void rebucketWeightsByRequestCountAndIgnoresEmptyWindows() {
        List<MetricPoint> raw = List.of(p(0, 10, 1, 100, 200, 250), p(1, 30, 3, 20, 40, 90), p(2, 0, 0, 0, 0, 0),
                p(3, 5, 0, 10, 10, 10));
        List<MetricPoint> b = MetricMath.rebucket(raw, 30);
        assertEquals(2, b.size());
        MetricPoint first = b.get(0);
        assertEquals(T0, first.windowStart());
        assertEquals(T0.plusSeconds(30), first.windowEnd());
        assertEquals(40, first.requestCount());
        assertEquals(4, first.errorCount());
        assertEquals((100 * 10 + 20 * 30) / 40.0, first.avgMs(), 1e-9);
        assertEquals((200 * 10 + 40 * 30) / 40.0, first.p95Ms(), 1e-9);
        assertEquals(250, first.maxMs());
        assertEquals(5, b.get(1).requestCount());
    }

    @Test
    void zeroTrafficBucketCarriesZerosAndNoTraffic() {
        MetricPoint z = MetricMath.rebucket(List.of(p(0, 0, 0, 0, 0, 0)), 60).get(0);
        assertEquals(0, z.requestCount());
        assertEquals(false, z.hasTraffic());
        assertEquals(0.0, z.avgMs());
    }

    @Test
    void rawWhenNoBucket() {
        List<MetricPoint> raw = List.of(p(0, 1, 0, 1, 1, 1));
        assertEquals(raw, MetricMath.rebucket(raw, null));
    }

    @Test
    void mergeByWindowSumsCallersMeasuringSameNode() {
        List<MetricPoint> merged = MetricMath.mergeByWindow(List.of(p(0, 10, 0, 10, 10, 10), p(0, 30, 6, 50, 90, 120),
                p(1, 4, 0, 5, 5, 5)));
        assertEquals(2, merged.size());
        assertEquals(40, merged.get(0).requestCount());
        assertEquals(6, merged.get(0).errorCount());
        assertEquals(40.0, merged.get(0).avgMs(), 1e-9);
        assertEquals(120, merged.get(0).maxMs());
    }

    @Test
    void bucketValidation() {
        MetricMath.validateBucket(null, 10);
        MetricMath.validateBucket(60, 10);
        assertThrows(ApiException.BadRequest.class, () -> MetricMath.validateBucket(15, 10));
        assertThrows(ApiException.BadRequest.class, () -> MetricMath.validateBucket(0, 10));
        assertThrows(ApiException.BadRequest.class, () -> MetricMath.validateBucket(7200, 10));
    }
}
