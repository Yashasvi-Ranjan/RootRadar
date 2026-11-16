-- V2: global default detection rules (service_id NULL). Applies in every profile. Owned by Agent 1.
-- Window length is 10 s (apifd.detection.window-seconds), so consecutive_windows=3 means ~30 s of sustained breach.
-- Latency thresholds are on p95 per window in milliseconds; error-rate thresholds are fractions (0.05 = 5 %).
INSERT INTO detection_rules
 (rule_key, metric, service_id, enabled, warning_threshold, critical_threshold, baseline_ratio, z_score_threshold,
  min_requests, consecutive_windows, critical_consecutive_windows, baseline_windows, min_baseline_windows, recovery_windows)
VALUES
 ('latency-p95-default',  'LATENCY_P95_MS', NULL, TRUE, 800,  1500, 3.0,  4.0, 3, 3, 2, 60, 12, 3),
 ('error-rate-default',   'ERROR_RATE',     NULL, TRUE, 0.05, 0.25, NULL, NULL, 5, 3, 2, 60, 12, 3),
 -- health rule: thresholds are consecutive FAILED CHECKS (warning=2, critical=3); window fields unused except recovery_windows
 ('health-failures-default','HEALTH_CONSECUTIVE_FAILURES', NULL, TRUE, 2, 3, NULL, NULL, 0, 1, 1, 1, 1, 3);
