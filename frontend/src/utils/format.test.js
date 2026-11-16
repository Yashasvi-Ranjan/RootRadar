import { describe, expect, it } from 'vitest';
import { bucketSecondsFor, formatDuration, formatMs, formatRate, rangeToParams, toChartRows } from './format.js';

describe('formatters', () => {
  it('formats latency', () => {
    expect(formatMs(null)).toBe('—');
    expect(formatMs(4)).toBe('4.0 ms');
    expect(formatMs(120.4)).toBe('120 ms');
    expect(formatMs(2011)).toBe('2.01 s');
  });
  it('formats ratios and durations', () => {
    expect(formatRate(0)).toBe('0.00%');
    expect(formatRate(0.25)).toBe('25.0%');
    expect(formatDuration(84)).toBe('1m 24s');
    expect(formatDuration(3700)).toBe('1h 1m');
  });
  it('computes range params and bucket sizes', () => {
    const now = new Date('2026-11-16T05:00:00Z');
    const r = rangeToParams('1h', now);
    expect(r.to).toBe('2026-11-16T05:00:00.000Z');
    expect(r.from).toBe('2026-11-16T04:00:00.000Z');
    expect(bucketSecondsFor(900)).toBeUndefined();
    expect(bucketSecondsFor(7 * 86400) % 10).toBe(0);
    expect(bucketSecondsFor(7 * 86400)).toBeLessThanOrEqual(3600);
  });
  it('leaves empty windows as gaps, never 0 ms', () => {
    const rows = toChartRows([
      { windowStart: '2026-11-16T05:00:00Z', requestCount: 0, errorCount: 0, avgMs: 0, p95Ms: 0, p99Ms: 0 },
      { windowStart: '2026-11-16T05:00:10Z', requestCount: 10, errorCount: 5, avgMs: 40, p95Ms: 80, p99Ms: 90 },
    ]);
    expect(rows[0].avg).toBeNull();
    expect(rows[1].errorRatePct).toBe(50);
  });
});
