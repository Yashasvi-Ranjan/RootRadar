package com.rootradar.apifd.anomaly;

import com.rootradar.apifd.monitoring.HealthSample;
import com.rootradar.apifd.telemetry.MetricSeries;
import java.time.Instant;
import java.util.List;

/**
 * Everything the detector needs; no clock, no DB. The caller (Agent 5's DetectionPipeline) assembles it from
 * MetricsQueryService.loadAllSeries(evaluatedAt - lookback, evaluatedAt), HealthQueryService.recentSamples and
 * DetectionRuleProvider.currentRules(). Series must contain COMPLETED windows only. The most recent windows of each
 * series are the "evaluation span"; older windows (up to rule.baselineWindows) form the baseline; the detector
 * must exclude the evaluation span from the baseline so an ongoing anomaly does not poison it.
 */
public record DetectionInput(
        Instant evaluatedAt,
        int windowSeconds,
        List<MetricSeries> series,
        List<HealthSample> healthSamples,
        DetectionRuleSet rules) {}
