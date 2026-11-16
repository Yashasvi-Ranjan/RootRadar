package com.rootradar.demo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FaultServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T04:30:00Z"));

    private FaultService svc(boolean db, double rnd) {
        return new FaultService("payment-service", clock, db, () -> rnd);
    }

    @Test
    void defaultsAreInactiveAndClearResets() {
        FaultService f = svc(true, 0.5);
        assertThat(f.state().active()).isFalse();
        f.replace(new FaultConfig(2000, 0, 0, 500, false, false, 0, null));
        assertThat(f.state().active()).isTrue();
        assertThat(f.state().latencyMs()).isEqualTo(2000);
        assertThat(f.clear().active()).isFalse();
    }

    @Test
    void errorRateUsesRandomDraw() {
        FaultConfig c = new FaultConfig(0, 0, 0.3, 500, false, false, 0, null);
        assertThat(svc(false, 0.1).shouldFail(c)).isTrue();
        assertThat(svc(false, 0.9).shouldFail(c)).isFalse();
        assertThat(svc(false, 0.0).shouldFail(FaultConfig.none())).isFalse();
    }

    @Test
    void jitterAddsToLatencyWithinBounds() {
        FaultConfig c = new FaultConfig(100, 50, 0, 500, false, false, 0, null);
        assertThat(svc(false, 0.0).latencyToInjectMs(c)).isEqualTo(100);
        assertThat(svc(false, 0.999).latencyToInjectMs(c)).isBetween(100L, 150L);
    }

    @Test
    void autoExpiresAfterDuration() {
        FaultService f = svc(false, 0.5);
        f.replace(new FaultConfig(10, 0, 0, 500, false, false, 0, 30));
        assertThat(f.state().expiresAt()).isEqualTo(Instant.parse("2026-11-16T04:30:30Z"));
        clock.advanceSeconds(29);
        assertThat(f.current().latencyMs()).isEqualTo(10);
        clock.advanceSeconds(1);
        assertThat(f.current().active()).isFalse();
    }

    @Test
    void healthOnlyDownWhenUnavailableAndAffectsHealth() {
        FaultService f = svc(false, 0.5);
        f.replace(new FaultConfig(0, 0, 0, 500, true, false, 0, null));
        assertThat(f.healthDown()).isFalse();
        f.replace(new FaultConfig(0, 0, 0, 500, true, true, 0, null));
        assertThat(f.healthDown()).isTrue();
    }

    @Test
    void validationRejectsOutOfRangeAndDbLatencyOutsidePayment() {
        FaultService f = svc(false, 0.5);
        assertThatThrownBy(() -> f.replace(new FaultConfig(30001, 0, 0, 500, false, false, 0, null)))
                .isInstanceOf(ApiException.Validation.class);
        assertThatThrownBy(() -> f.replace(new FaultConfig(0, 0, 1.5, 500, false, false, 0, null)))
                .isInstanceOf(ApiException.Validation.class);
        assertThatThrownBy(() -> f.replace(new FaultConfig(0, 0, 0, 404, false, false, 0, null)))
                .isInstanceOf(ApiException.Validation.class);
        assertThatThrownBy(() -> f.replace(new FaultConfig(0, 0, 0, 500, false, false, 100, null)))
                .hasMessageContaining("payment-service");
        assertThatThrownBy(() -> f.replace(new FaultConfig(0, 0, 0, 500, false, false, 0, 0)))
                .isInstanceOf(ApiException.Validation.class);
        svc(true, 0.5).replace(new FaultConfig(0, 0, 0, 500, false, false, 100, null));
    }
}
