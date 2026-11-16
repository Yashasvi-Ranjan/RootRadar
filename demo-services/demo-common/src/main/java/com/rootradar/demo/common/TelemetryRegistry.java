package com.rootradar.demo.common;

import com.rootradar.demo.common.TelemetryReport.DependencyStats;
import com.rootradar.demo.common.TelemetryReport.EndpointStats;
import com.rootradar.demo.common.TelemetryReport.Overall;
import com.rootradar.demo.common.TelemetryReport.Window;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Real, in-process windowed telemetry. Samples are bucketed into fixed 10 s windows aligned to epoch multiples (by the
 * time the measured operation finished). At most {@link #RETAINED_WINDOWS} completed windows (plus the one in
 * progress) are kept, so memory is bounded. Percentiles are exact (nearest-rank) over the raw samples of a window;
 * a per-series sample cap ({@link #SAMPLE_CAP}) bounds memory under extreme load (count/avg/max stay exact, percentiles
 * then become approximate for that window).
 */
public class TelemetryRegistry {
    public static final int WINDOW_SECONDS = 10;
    public static final int RETAINED_WINDOWS = 60;
    public static final int SAMPLE_CAP = 10_000;

    private final String service;
    private final Clock clock;
    private final Instant startedAt;
    private final ConcurrentSkipListMap<Long, WindowAcc> windows = new ConcurrentSkipListMap<>();

    public TelemetryRegistry(String service, Clock clock) {
        this.service = service;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    public String service() {
        return service;
    }

    public void recordEndpoint(String method, String endpoint, double ms, boolean error) {
        WindowAcc w = current();
        w.overall.add(ms, error, false);
        w.endpoints.computeIfAbsent(method + " " + endpoint, k -> new Series()).add(ms, error, false);
    }

    public void recordDependency(String name, String type, double ms, boolean error, boolean timeout) {
        WindowAcc w = current();
        w.dependencies.computeIfAbsent(name + "|" + type, k -> new Series()).add(ms, error, timeout);
    }

    /** Convenience: times a dependency call; any exception counts as an error and is rethrown. */
    public <T> T timeDependency(String name, String type, java.util.concurrent.Callable<T> call) throws Exception {
        long t0 = System.nanoTime();
        boolean error = false;
        try {
            return call.call();
        } catch (Exception e) {
            error = true;
            throw e;
        } finally {
            recordDependency(name, type, (System.nanoTime() - t0) / 1e6, error, false);
        }
    }

    private WindowAcc current() {
        long idx = clock.millis() / (WINDOW_SECONDS * 1000L);
        WindowAcc w = windows.computeIfAbsent(idx, k -> new WindowAcc());
        windows.headMap(idx - RETAINED_WINDOWS, false).clear();
        return w;
    }

    /** Completed windows with windowEnd &gt; since (all retained when since is null), oldest first. */
    public TelemetryReport report(Instant since) {
        long nowMs = clock.millis();
        long idx = nowMs / (WINDOW_SECONDS * 1000L);
        windows.headMap(idx - RETAINED_WINDOWS, false).clear();
        List<Window> out = new ArrayList<>();
        for (Map.Entry<Long, WindowAcc> e : windows.headMap(idx, false).entrySet()) {
            Instant start = Instant.ofEpochMilli(e.getKey() * WINDOW_SECONDS * 1000L);
            Instant end = start.plusSeconds(WINDOW_SECONDS);
            if (since != null && !end.isAfter(since)) {
                continue;
            }
            WindowAcc w = e.getValue();
            List<EndpointStats> eps = new ArrayList<>();
            for (Map.Entry<String, Series> s : new TreeMap<>(w.endpoints).entrySet()) {
                Snap n = s.getValue().snap();
                int sp = s.getKey().indexOf(' ');
                eps.add(new EndpointStats(s.getKey().substring(0, sp), s.getKey().substring(sp + 1), n.count,
                        n.errors, n.avg(), n.pct(0.50), n.pct(0.95), n.pct(0.99), n.max));
            }
            List<DependencyStats> deps = new ArrayList<>();
            for (Map.Entry<String, Series> s : new TreeMap<>(w.dependencies).entrySet()) {
                Snap n = s.getValue().snap();
                int sp = s.getKey().indexOf('|');
                deps.add(new DependencyStats(s.getKey().substring(0, sp), s.getKey().substring(sp + 1), n.count,
                        n.errors, n.timeouts, n.avg(), n.pct(0.50), n.pct(0.95), n.pct(0.99), n.max));
            }
            Snap o = w.overall.snap();
            out.add(new Window(start, end,
                    new Overall(o.count, o.errors, o.avg(), o.pct(0.50), o.pct(0.95), o.pct(0.99), o.max), eps, deps));
        }
        return new TelemetryReport(1, service, Instant.ofEpochMilli(nowMs), startedAt, WINDOW_SECONDS, out);
    }

    private static final class WindowAcc {
        final Series overall = new Series();
        final ConcurrentSkipListMap<String, Series> endpoints = new ConcurrentSkipListMap<>();
        final ConcurrentSkipListMap<String, Series> dependencies = new ConcurrentSkipListMap<>();
    }

    private record Snap(long count, long errors, long timeouts, double sum, double max, double[] sorted) {
        double avg() {
            return count == 0 ? 0 : round(sum / count);
        }

        /** Nearest-rank percentile. */
        double pct(double p) {
            if (sorted.length == 0) {
                return 0;
            }
            int rank = (int) Math.ceil(p * sorted.length);
            return round(sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))]);
        }
    }

    private static final class Series {
        private long count;
        private long errors;
        private long timeouts;
        private double sum;
        private double max;
        private double[] samples = new double[16];
        private int n;

        synchronized void add(double ms, boolean error, boolean timeout) {
            count++;
            sum += ms;
            max = Math.max(max, ms);
            if (error) {
                errors++;
            }
            if (timeout) {
                timeouts++;
            }
            if (n < SAMPLE_CAP) {
                if (n == samples.length) {
                    samples = Arrays.copyOf(samples, Math.min(SAMPLE_CAP, n * 2));
                }
                samples[n++] = ms;
            }
        }

        synchronized Snap snap() {
            double[] copy = Arrays.copyOf(samples, n);
            Arrays.sort(copy);
            return new Snap(count, errors, timeouts, sum, round(max), copy);
        }
    }

    static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
