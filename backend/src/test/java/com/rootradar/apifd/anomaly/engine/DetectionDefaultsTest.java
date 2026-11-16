package com.rootradar.apifd.anomaly.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootradar.apifd.anomaly.RuleMetric;
import org.junit.jupiter.api.Test;

class DetectionDefaultsTest {
    @Test
    void defaultsMatchMigrationV2Values() {
        var set = DetectionDefaults.ruleSet();
        var lat = set.resolve("any", RuleMetric.LATENCY_P95_MS).orElseThrow();
        assertThat(lat.warningThreshold()).isEqualTo(800.0);
        assertThat(lat.criticalThreshold()).isEqualTo(1500.0);
        assertThat(lat.baselineRatio()).isEqualTo(3.0);
        assertThat(lat.zScoreThreshold()).isEqualTo(4.0);
        assertThat(lat.consecutiveWindows()).isEqualTo(3);
        assertThat(lat.criticalConsecutiveWindows()).isEqualTo(2);
        assertThat(lat.baselineWindows()).isEqualTo(60);
        assertThat(lat.minBaselineWindows()).isEqualTo(12);
        var err = set.resolve("any", RuleMetric.ERROR_RATE).orElseThrow();
        assertThat(err.warningThreshold()).isEqualTo(0.05);
        assertThat(err.criticalThreshold()).isEqualTo(0.25);
        assertThat(err.minRequests()).isEqualTo(5);
        var h = set.resolve("any", RuleMetric.HEALTH_CONSECUTIVE_FAILURES).orElseThrow();
        assertThat(h.warningThreshold()).isEqualTo(2.0);
        assertThat(h.criticalThreshold()).isEqualTo(3.0);
    }
}
