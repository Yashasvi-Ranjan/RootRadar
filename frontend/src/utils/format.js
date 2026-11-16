export const dash = '—';

export function formatMs(v) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return dash;
  const n = Number(v);
  if (n >= 1000) return `${(n / 1000).toFixed(2)} s`;
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ms`;
}

/** ratio 0..1 -> "1.25%" */
export function formatRate(v) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return dash;
  const pct = Number(v) * 100;
  return `${pct < 10 ? pct.toFixed(2) : pct.toFixed(1)}%`;
}

/** already a percent value (e.g. 99.8) */
export function formatPercent(v) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return dash;
  return `${Number(v).toFixed(2)}%`;
}

export function formatDateTime(iso) {
  if (!iso) return dash;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return dash;
  return d.toLocaleString(undefined, { hour12: false });
}

export function formatTime(iso) {
  if (!iso) return dash;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return dash;
  return d.toLocaleTimeString(undefined, { hour12: false });
}

export function formatDuration(seconds) {
  if (seconds === null || seconds === undefined || Number.isNaN(Number(seconds))) return dash;
  let s = Math.max(0, Math.round(Number(seconds)));
  const h = Math.floor(s / 3600);
  s -= h * 3600;
  const m = Math.floor(s / 60);
  s -= m * 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

export function formatNumber(v) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return dash;
  return Number(v).toLocaleString();
}

export const RANGES = [
  { key: '15m', label: 'Last 15 min', seconds: 900 },
  { key: '1h', label: 'Last hour', seconds: 3600 },
  { key: '6h', label: 'Last 6 hours', seconds: 6 * 3600 },
  { key: '24h', label: 'Last 24 hours', seconds: 24 * 3600 },
  { key: '7d', label: 'Last 7 days', seconds: 7 * 24 * 3600 },
];

export function rangeToParams(rangeKey, now = new Date()) {
  const r = RANGES.find((x) => x.key === rangeKey) || RANGES[1];
  return {
    from: new Date(now.getTime() - r.seconds * 1000).toISOString(),
    to: now.toISOString(),
    seconds: r.seconds,
  };
}

/** Bucket size (multiple of 10 s, <= 3600) so a chart has roughly `target` points; undefined = raw 10 s windows. */
export function bucketSecondsFor(spanSeconds, target = 240) {
  const raw = Math.ceil(spanSeconds / target / 10) * 10;
  if (raw <= 10) return undefined;
  return Math.min(3600, raw);
}

/** Chart rows from MetricPoint[]; windows without requests are null (gap), never 0 ms. */
export function toChartRows(points) {
  return (points || []).map((p) => {
    const has = p.requestCount > 0;
    return {
      t: new Date(p.windowStart).getTime(),
      avg: has ? p.avgMs : null,
      p95: has ? p.p95Ms : null,
      p99: has ? p.p99Ms : null,
      errorRatePct: has ? (p.errorCount / p.requestCount) * 100 : null,
      requestCount: p.requestCount,
    };
  });
}

export function classLabel(c) {
  return (c || '').toLowerCase().replace(/_/g, ' ');
}
