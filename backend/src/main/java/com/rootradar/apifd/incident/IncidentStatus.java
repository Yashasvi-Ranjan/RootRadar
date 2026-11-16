package com.rootradar.apifd.incident;

/**
 * Lifecycle: DETECTED -> ACTIVE -> RECOVERING -> RESOLVED.
 * DETECTED: anomaly just opened the incident, not yet confirmed.
 * ACTIVE: confirmed (still anomalous after apifd.incident.confirm-evaluations evaluations, or CRITICAL severity).
 * RECOVERING: all related series NORMAL, waiting for apifd.incident.recovery-evaluations consecutive normal evaluations
 *             (hysteresis); any new anomaly sends it back to ACTIVE (timeline RECOVERY_ABORTED).
 * RESOLVED: terminal, except reopen within apifd.incident.reopen-window-seconds (timeline REOPENED, reopenCount++).
 * A DETECTED incident that clears before confirmation goes straight to RESOLVED (resolutionReason RECOVERED).
 */
public enum IncidentStatus {
    DETECTED, ACTIVE, RECOVERING, RESOLVED;

    public boolean isOpen() { return this != RESOLVED; }
}
