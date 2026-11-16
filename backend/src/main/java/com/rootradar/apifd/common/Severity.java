package com.rootradar.apifd.common;

/** Shared severity scale for anomalies and incidents. CRITICAL &gt; WARNING. */
public enum Severity {
    WARNING, CRITICAL;

    public boolean isAtLeast(Severity other) {
        return this.ordinal() >= other.ordinal();
    }
}
