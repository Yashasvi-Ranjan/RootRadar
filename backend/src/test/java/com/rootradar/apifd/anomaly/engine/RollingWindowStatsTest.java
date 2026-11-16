package com.rootradar.apifd.anomaly.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RollingWindowStatsTest {

    @Test
    void medianMadMeanStdDev() {
        RollingWindowStats s = RollingWindowStats.of(List.of(1.0, 2.0, 3.0, 4.0, 100.0));
        assertThat(s.count()).isEqualTo(5);
        assertThat(s.median()).isEqualTo(3.0);
        assertThat(s.mad()).isEqualTo(1.0); // |dev| = 2,1,0,1,97 -> median 1
        assertThat(s.mean()).isEqualTo(22.0);
        assertThat(s.min()).isEqualTo(1.0);
        assertThat(s.max()).isEqualTo(100.0);
        assertThat(s.stdDev()).isGreaterThan(30.0);
    }

    @Test
    void evenCountMedianAveragesMiddle() {
        assertThat(RollingWindowStats.of(List.of(4.0, 1.0, 3.0, 2.0)).median()).isEqualTo(2.5);
    }

    @Test
    void emptyIsNanSafe() {
        RollingWindowStats s = RollingWindowStats.of(List.of());
        assertThat(s.count()).isZero();
        assertThat(s.median()).isNaN();
        assertThat(s.robustScale(0.1, 1.0)).isEqualTo(1.0);
    }

    @Test
    void robustScaleFloorsPreventDivisionByZeroOnFlatBaseline() {
        RollingWindowStats flat = RollingWindowStats.of(List.of(50.0, 50.0, 50.0, 50.0));
        assertThat(flat.mad()).isZero();
        assertThat(flat.robustScale(0.1, 1.0)).isEqualTo(5.0);
        assertThat(flat.robustZ(100.0, 0.1, 1.0)).isEqualTo(10.0);
    }

    @Test
    void robustStatisticsResistOutliersWhereMeanDoesNot() {
        RollingWindowStats s = RollingWindowStats.of(List.of(10.0, 10.0, 11.0, 10.0, 9.0, 10.0, 5000.0));
        assertThat(s.median()).isEqualTo(10.0);
        assertThat(s.mean()).isGreaterThan(500.0);
    }
}
