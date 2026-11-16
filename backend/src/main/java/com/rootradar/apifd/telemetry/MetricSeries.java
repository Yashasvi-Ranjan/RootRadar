package com.rootradar.apifd.telemetry;

import java.util.List;

/** A series: points sorted ascending by windowStart, one per window, no duplicates. May contain zero-traffic windows. */
public record MetricSeries(SeriesKey key, List<MetricPoint> points) {}
