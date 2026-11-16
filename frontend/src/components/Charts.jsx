import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { formatDateTime, formatTime, toChartRows } from '../utils/format.js';
import { Empty } from './common.jsx';

const COLORS = { avg: '#1f5fa8', p95: '#a8630a', p99: '#b3261e', err: '#b3261e' };

function ChartFrame({ title, hasData, children, note }) {
  return (
    <div className="panel">
      <h3>{title}</h3>
      {hasData ? (
        <div style={{ width: '100%', height: 240 }}>
          <ResponsiveContainer>{children}</ResponsiveContainer>
        </div>
      ) : <Empty>No request data in this range.</Empty>}
      {note && <div className="muted small" style={{ marginTop: 6 }}>{note}</div>}
    </div>
  );
}

const axisTime = (t) => formatTime(new Date(t).toISOString());
const labelTime = (t) => formatDateTime(new Date(t).toISOString());

/** Latency (avg/p95/p99) and error-rate charts from a MetricsSeriesResponse-like `points` array. */
export function MetricsCharts({ points, bucketSeconds }) {
  const rows = toChartRows(points);
  const hasData = rows.some((r) => r.avg !== null);
  const note = `Windows without requests are left as gaps. Percentiles per bucket are count-weighted and approximate${bucketSeconds ? ` (${bucketSeconds}s buckets)` : ' (raw 10s windows)'}.`;
  return (
    <>
      <ChartFrame title="Latency (ms)" hasData={hasData} note={note}>
        <LineChart data={rows} margin={{ top: 5, right: 12, bottom: 0, left: 0 }}>
          <CartesianGrid stroke="#e8ebef" strokeDasharray="3 3" />
          <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={axisTime} tick={{ fontSize: 11 }} />
          <YAxis tick={{ fontSize: 11 }} width={48} />
          <Tooltip labelFormatter={labelTime} formatter={(v) => (v == null ? '—' : `${Number(v).toFixed(1)} ms`)} />
          <Legend />
          <Line type="linear" dataKey="avg" name="avg" stroke={COLORS.avg} dot={false} strokeWidth={2} connectNulls={false} isAnimationActive={false} />
          <Line type="linear" dataKey="p95" name="p95" stroke={COLORS.p95} dot={false} strokeWidth={1.5} connectNulls={false} isAnimationActive={false} />
          <Line type="linear" dataKey="p99" name="p99" stroke={COLORS.p99} dot={false} strokeWidth={1.5} strokeDasharray="4 3" connectNulls={false} isAnimationActive={false} />
        </LineChart>
      </ChartFrame>
      <ChartFrame title="Error rate (%)" hasData={hasData}>
        <LineChart data={rows} margin={{ top: 5, right: 12, bottom: 0, left: 0 }}>
          <CartesianGrid stroke="#e8ebef" strokeDasharray="3 3" />
          <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={axisTime} tick={{ fontSize: 11 }} />
          <YAxis tick={{ fontSize: 11 }} width={48} domain={[0, 'auto']} />
          <Tooltip labelFormatter={labelTime} formatter={(v) => (v == null ? '—' : `${Number(v).toFixed(2)} %`)} />
          <Line type="linear" dataKey="errorRatePct" name="error rate" stroke={COLORS.err} dot={false} strokeWidth={2} connectNulls={false} isAnimationActive={false} />
        </LineChart>
      </ChartFrame>
    </>
  );
}
