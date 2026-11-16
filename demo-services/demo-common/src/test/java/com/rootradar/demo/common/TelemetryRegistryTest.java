package com.rootradar.demo.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootradar.demo.common.TelemetryReport.Window;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TelemetryRegistryTest {
    // 2026-11-16T04:30:00Z is a multiple of 10 s
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T04:30:00Z"));
    private final TelemetryRegistry reg = new TelemetryRegistry("order-service", clock);

    @Test
    void currentWindowIsNotReportedUntilComplete() {
        reg.recordEndpoint("POST", "/api/orders", 10, false);
        assertThat(reg.report(null).windows()).isEmpty();
        clock.advanceSeconds(10);
        assertThat(reg.report(null).windows()).hasSize(1);
    }

    @Test
    void nearestRankPercentilesAreExact() {
        for (int i = 1; i <= 100; i++) {
            reg.recordEndpoint("POST", "/api/orders", i, i > 95); // errors: 96..100
        }
        clock.advanceSeconds(10);
        TelemetryReport r = reg.report(null);
        Window w = r.windows().get(0);
        assertThat(w.windowStart()).isEqualTo(Instant.parse("2026-11-16T04:30:00Z"));
        assertThat(w.windowEnd()).isEqualTo(Instant.parse("2026-11-16T04:30:10Z"));
        var o = w.overall();
        assertThat(o.count()).isEqualTo(100);
        assertThat(o.errorCount()).isEqualTo(5);
        assertThat(o.avgMs()).isEqualTo(50.5);
        assertThat(o.p50Ms()).isEqualTo(50);
        assertThat(o.p95Ms()).isEqualTo(95);
        assertThat(o.p99Ms()).isEqualTo(99);
        assertThat(o.maxMs()).isEqualTo(100);
        assertThat(w.endpoints()).hasSize(1);
        assertThat(w.endpoints().get(0).endpoint()).isEqualTo("/api/orders");
        assertThat(w.dependencies()).isEmpty();
        assertThat(r.service()).isEqualTo("order-service");
        assertThat(r.windowSeconds()).isEqualTo(10);
    }

    @Test
    void dependencyErrorsAndTimeoutsAreCountedSeparately() {
        reg.recordDependency("payment-service", "HTTP", 40, false, false);
        reg.recordDependency("payment-service", "HTTP", 5000, true, true);
        reg.recordDependency("payment-service", "HTTP", 30, true, false);
        clock.advanceSeconds(10);
        var d = reg.report(null).windows().get(0).dependencies().get(0);
        assertThat(d.name()).isEqualTo("payment-service");
        assertThat(d.count()).isEqualTo(3);
        assertThat(d.errorCount()).isEqualTo(2);
        assertThat(d.timeoutCount()).isEqualTo(1);
        assertThat(d.maxMs()).isEqualTo(5000);
        // dependency-only samples do not create endpoint or overall counts
        assertThat(reg.report(null).windows().get(0).overall().count()).isZero();
    }

    @Test
    void sinceFiltersAndRingIsBoundedTo60Windows() {
        for (int i = 0; i < 70; i++) {
            reg.recordEndpoint("GET", "/api/orders/{id}", 1, false);
            clock.advanceSeconds(10);
        }
        TelemetryReport all = reg.report(null);
        assertThat(all.windows()).hasSize(60);
        assertThat(all.windows().get(0).windowStart()).isBefore(all.windows().get(59).windowStart());
        Instant lastEnd = all.windows().get(59).windowEnd();
        assertThat(reg.report(lastEnd.minusSeconds(10)).windows()).hasSize(1);
        assertThat(reg.report(lastEnd).windows()).isEmpty();
    }

    @Test
    void samplesAreSplitAcrossWindowsAtBoundaries() {
        reg.recordEndpoint("GET", "/x", 1, false);
        clock.advanceSeconds(10);
        reg.recordEndpoint("GET", "/x", 2, false);
        clock.advanceSeconds(10);
        var ws = reg.report(null).windows();
        assertThat(ws).hasSize(2);
        assertThat(ws.get(0).overall().maxMs()).isEqualTo(1);
        assertThat(ws.get(1).overall().maxMs()).isEqualTo(2);
    }
}
