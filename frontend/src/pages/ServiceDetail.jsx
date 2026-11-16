import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../services/api.js';
import { useApi } from '../hooks/useApi.js';
import { Async, Badge, DemoBadge, Empty, Field, HealthBadge, Pagination, RangeSelect } from '../components/common.jsx';
import { MetricsCharts } from '../components/Charts.jsx';
import {
  RANGES, bucketSecondsFor, formatDateTime, formatMs, formatNumber, formatPercent, formatRate, formatTime, rangeToParams,
} from '../utils/format.js';

// AvailabilityReport (docs/API_CONTRACTS.md section 2): {uptimePercent, buckets:[{start,end,uptimePercent,...}]}.
// Buckets without checks have uptimePercent null and are skipped (never plotted as 0 %).
function availabilityPoints(rep) {
  const arr = rep && rep.buckets;
  if (!Array.isArray(arr)) return [];
  return arr.map((p) => ({ t: new Date(p.start).getTime(), pct: typeof p.uptimePercent === 'number' ? p.uptimePercent : null }))
    .filter((r) => Number.isFinite(r.t) && r.pct !== null);
}

export default function ServiceDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const [rangeKey, setRangeKey] = useState('1h');
  const [endpoint, setEndpoint] = useState(''); // "METHOD path" or ''
  const [healthPage, setHealthPage] = useState(0);
  const [checkMsg, setCheckMsg] = useState(null);
  // `anchor` fixes the time window per user action so refetches on live events are stable and cheap.
  const [anchor, setAnchor] = useState(() => new Date());
  const range = useMemo(() => rangeToParams(rangeKey, anchor), [rangeKey, anchor]);
  const bucketSeconds = bucketSecondsFor(range.seconds);
  const [method, path] = endpoint ? endpoint.split(' ') : [undefined, undefined];

  const all = useApi((s) => api.listServices(s), []);
  const svc = useApi((s) => api.getService(id, s), [id], ['health.updated', 'telemetry.updated']);
  const dep = svc.data && svc.data.service.nodeType === 'DEPENDENCY_ONLY';
  const endpoints = useApi((s) => api.serviceEndpoints(id, { from: range.from, to: range.to }, s), [id, range.from], ['telemetry.updated']);
  const metrics = useApi((s) => api.serviceMetrics(id, { from: range.from, to: range.to, bucketSeconds, method, endpoint: path }, s),
    [id, range.from, endpoint], ['telemetry.updated']);
  const health = useApi((s) => (dep ? Promise.resolve(null) : api.serviceHealth(id, { from: range.from, to: range.to, page: healthPage, size: 10 }, s)),
    [id, range.from, healthPage, dep], ['health.updated']);
  const avail = useApi((s) => (dep ? Promise.resolve(null) : api.serviceAvailability(id, { from: range.from, to: range.to, bucketMinutes: Math.max(5, Math.ceil(range.seconds / 60 / 120)) }, s)),
    [id, range.from, dep]);

  const changeRange = (k) => { setRangeKey(k); setAnchor(new Date()); setHealthPage(0); };
  const changeService = (e) => nav(`/services/${e.target.value}`);

  const check = async () => {
    setCheckMsg(null);
    try {
      const r = await api.checkService(id);
      setCheckMsg(`Check result: ${r.status} (${r.outcome}${r.responseTimeMs != null ? `, ${formatMs(r.responseTimeMs)}` : ''}${r.errorMessage ? `, ${r.errorMessage}` : ''})`);
      svc.reload(); health.reload();
    } catch (e) { setCheckMsg(`Check failed: ${e.message}`); }
  };

  return (
    <Async q={svc}>
      {(o) => {
        const s = o.service;
        const apts = availabilityPoints(avail.data);
        const availPct = avail.data && typeof avail.data.uptimePercent === 'number' ? avail.data.uptimePercent : null;
        return (
          <>
            <div className="page-head">
              <div>
                <div className="small"><Link to="/services">Services</Link> /</div>
                <h1>{s.displayName || s.name} <DemoBadge demo={s.demo} /> {dep && <Badge tone="plain">dependency only</Badge>}</h1>
                <div className="muted mono">{s.name}{s.baseUrl ? ` · ${s.baseUrl}${s.healthEndpoint || ''}` : ''}</div>
                {s.description && <div className="muted">{s.description}</div>}
              </div>
              <div className="actions">
                {!dep && <button onClick={check}>Check now</button>}
              </div>
            </div>
            {checkMsg && <div className="note">{checkMsg}</div>}

            <div className="panel filters" style={{ marginBottom: 16 }}>
              <Field id="svc-pick" label="Service">
                <select id="svc-pick" value={id} onChange={changeService}>
                  {(all.data || [{ service: s }]).map((x) => <option key={x.service.id} value={x.service.id}>{x.service.name}</option>)}
                </select>
              </Field>
              <RangeSelect value={rangeKey} onChange={changeRange} ranges={RANGES} />
              <Field id="ep-pick" label="Endpoint">
                <select id="ep-pick" value={endpoint} onChange={(e) => setEndpoint(e.target.value)}>
                  <option value="">All (service-wide)</option>
                  {(endpoints.data || []).map((e) => (
                    <option key={`${e.method} ${e.endpoint}`} value={`${e.method} ${e.endpoint}`}>{e.method} {e.endpoint}</option>
                  ))}
                </select>
              </Field>
              <button onClick={() => setAnchor(new Date())}>Refresh window</button>
            </div>

            <div className="panel">
              <h2>Current state</h2>
              <dl className="kv">
                {!dep && <><dt>Health check</dt><dd><HealthBadge status={o.health && o.health.status} /> {o.health && <span className="muted small">{o.health.outcome} · HTTP {o.health.httpStatus ?? '—'} · {formatMs(o.health.responseTimeMs)} · {formatDateTime(o.health.checkedAt)}</span>}</dd></>}
                <dt>Availability (24h)</dt><dd>{formatPercent(o.availability24h)}</dd>
                <dt>Last 5 minutes</dt>
                <dd>{o.metrics ? `${formatNumber(o.metrics.requestCount)} requests · errors ${formatRate(o.metrics.errorRate)} · avg ${formatMs(o.metrics.avgMs)} · p95 ${formatMs(o.metrics.p95Ms)} · p99 ${formatMs(o.metrics.p99Ms)}` : 'no telemetry'}</dd>
                {dep && <><dt>Metric source</dt><dd>Measured by calling services (caller measurements); this node is not polled.</dd></>}
              </dl>
            </div>

            <h2>Latency and errors{endpoint ? `: ${endpoint}` : ' (service-wide)'}</h2>
            <Async q={metrics} isEmpty={(d) => !d.points || d.points.length === 0} empty="No metrics recorded in this time range.">
              {(d) => <MetricsCharts points={d.points} bucketSeconds={d.bucketSeconds} />}
            </Async>

            <div className="panel">
              <h2>Endpoints</h2>
              <Async q={endpoints} isEmpty={(d) => d.length === 0} empty="No endpoint telemetry in this range.">
                {(d) => (
                  <div className="table-wrap"><table>
                    <thead><tr><th>Endpoint</th><th className="num">Requests</th><th className="num">Error rate</th><th className="num">avg</th><th className="num">p95</th><th className="num">p99</th></tr></thead>
                    <tbody>{d.map((row) => {
                      // EndpointSummary = {method, endpoint, summary: MetricsSummary}
                      const e = { ...row.summary, method: row.method, endpoint: row.endpoint };
                      const key = `${e.method} ${e.endpoint}`;
                      const rate = e.errorRate ?? (e.requestCount ? e.errorCount / e.requestCount : null);
                      return (
                        <tr key={key} className={`clickable ${endpoint === key ? 'selected' : ''}`} onClick={() => setEndpoint(endpoint === key ? '' : key)}>
                          <td className="mono">{key}</td><td className="num">{formatNumber(e.requestCount)}</td>
                          <td className="num">{formatRate(rate)}</td><td className="num">{formatMs(e.avgMs)}</td>
                          <td className="num">{formatMs(e.p95Ms)}</td><td className="num">{formatMs(e.p99Ms)}</td>
                        </tr>
                      );
                    })}</tbody>
                  </table></div>
                )}
              </Async>
              <div className="muted small" style={{ marginTop: 6 }}>Click a row to chart that endpoint.</div>
            </div>

            {!dep && (
              <>
                <div className="panel">
                  <h2>Availability{availPct !== null ? `: ${formatPercent(availPct)} in range` : ''}</h2>
                  <Async q={avail}>
                    {() => apts.length === 0 ? <Empty>No availability buckets in this range.</Empty> : (
                      <div style={{ height: 180 }}><ResponsiveContainer>
                        <LineChart data={apts}>
                          <CartesianGrid stroke="#e8ebef" strokeDasharray="3 3" />
                          <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={(t) => formatTime(new Date(t).toISOString())} tick={{ fontSize: 11 }} />
                          <YAxis domain={[0, 100]} tick={{ fontSize: 11 }} width={40} />
                          <Tooltip labelFormatter={(t) => formatDateTime(new Date(t).toISOString())} formatter={(v) => `${Number(v).toFixed(1)} %`} />
                          <Line type="stepAfter" dataKey="pct" stroke="#2f7d4f" dot={false} strokeWidth={2} isAnimationActive={false} />
                        </LineChart>
                      </ResponsiveContainer></div>
                    )}
                  </Async>
                </div>
                <div className="panel">
                  <h2>Health check history</h2>
                  <Async q={health} isEmpty={(d) => !d.items || d.items.length === 0} empty="No health checks recorded in this range.">
                    {(d) => (
                      <>
                        <div className="table-wrap"><table>
                          <thead><tr><th>Checked at</th><th>Status</th><th>Outcome</th><th className="num">HTTP</th><th className="num">Response</th><th>Error</th></tr></thead>
                          <tbody>{d.items.map((h) => (
                            <tr key={h.id}><td>{formatDateTime(h.checkedAt)}</td><td><HealthBadge status={h.status} /></td><td>{h.outcome}</td>
                              <td className="num">{h.httpStatus ?? '—'}</td><td className="num">{formatMs(h.responseTimeMs)}</td><td>{h.errorMessage || ''}</td></tr>
                          ))}</tbody>
                        </table></div>
                        <Pagination page={d.page} totalPages={d.totalPages} totalItems={d.totalItems} onPage={setHealthPage} />
                      </>
                    )}
                  </Async>
                </div>
              </>
            )}
          </>
        );
      }}
    </Async>
  );
}
