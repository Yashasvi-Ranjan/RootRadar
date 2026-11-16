package com.rootradar.apifd.anomaly.engine;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable descriptive statistics of a window of values (pure, no dependencies). Robust statistics (median, MAD)
 * are the primary baseline; mean/stddev are provided for evidence and for tests.
 *
 * @param count  number of values
 * @param median 50th percentile (average of the two middle values for even counts); NaN when count == 0
 * @param mad    median absolute deviation around the median (NOT scaled); NaN when count == 0
 */
public record RollingWindowStats(int count, double median, double mad, double mean, double stdDev, double min, double max) {

    /** Scale factor making MAD a consistent estimator of the standard deviation for normally distributed data. */
    public static final double MAD_TO_SIGMA = 1.4826;

    public static final RollingWindowStats EMPTY =
            new RollingWindowStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    public static RollingWindowStats of(List<Double> values) {
        if (values == null || values.isEmpty()) return EMPTY;
        double[] a = new double[values.size()];
        for (int i = 0; i < a.length; i++) a[i] = values.get(i);
        double sum = 0;
        for (double v : a) sum += v;
        double mean = sum / a.length;
        double sq = 0;
        for (double v : a) sq += (v - mean) * (v - mean);
        double std = Math.sqrt(sq / a.length);
        double median = median(a);
        double[] dev = new double[a.length];
        for (int i = 0; i < a.length; i++) dev[i] = Math.abs(a[i] - median);
        double mad = median(dev);
        double[] sorted = a.clone();
        Arrays.sort(sorted);
        return new RollingWindowStats(a.length, median, mad, mean, std, sorted[0], sorted[sorted.length - 1]);
    }

    static double median(double[] values) {
        double[] s = values.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    /**
     * Robust scale = max(1.4826 * MAD, relativeFloor * |median|, absoluteFloor). The floors stop a perfectly flat
     * baseline (MAD = 0) from turning microscopic noise into an enormous z-score.
     */
    public double robustScale(double relativeFloor, double absoluteFloor) {
        if (count == 0) return absoluteFloor;
        return Math.max(Math.max(MAD_TO_SIGMA * mad, relativeFloor * Math.abs(median)), absoluteFloor);
    }

    /** Robust z-score (value - median) / scale. */
    public double robustZ(double value, double relativeFloor, double absoluteFloor) {
        return (value - median) / robustScale(relativeFloor, absoluteFloor);
    }
}
