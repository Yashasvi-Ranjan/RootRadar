package com.rootradar.apifd.anomaly;

/** Which technique triggered the anomaly (an event reports the strongest/first one that fired). */
public enum DetectionMethod { STATIC_THRESHOLD, BASELINE_RATIO, ROBUST_Z_SCORE, CONSECUTIVE_FAILURES }
