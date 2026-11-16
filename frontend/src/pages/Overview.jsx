import { Link } from 'react-router-dom';
import { api } from '../services/api.js';
import { useApi } from '../hooks/useApi.js';
import { useLive } from '../hooks/LiveProvider.jsx';
import { Async, DemoBadge, HealthBadge, Badge } from '../components/common.jsx';
import { IncidentTable } from '../components/IncidentParts.jsx';
import { formatMs, formatPercent, formatRate, formatTime } from '../utils/format.js';

export default function Overview() {
  const { status } = useLive();
  const services = useApi((s) => api.listServices(s), [], ['health.updated', 'telemetry.updated']);
  const incidents = useApi((s) => api.activeIncidents(s), [], ['incident.opened', 'incident.updated', 'incident.resolved']);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Overview</h1>
          <div className="muted">Service health, recent performance (last 5 minutes) and active incidents.</div>
        </div>
        <div className="live"><span className={`dot ${status === 'connected' ? 'ok' : status === 'connecting' ? 'warn' : 'bad'}`} />
          Live updates: {status}</div>
      </div>

      <div className="panel">
        <h2>Active incidents</h2>
        <Async q={incidents} isEmpty={(d) => d.length === 0} empty="No active incidents.">
          {(d) => <IncidentTable incidents={d} />}
        </Async>
      </div>

      <div className="panel">
        <h2>Service health</h2>
        <Async q={services} isEmpty={(d) => d.length === 0} empty={<>No services registered. <Link to="/services">Register one.</Link></>}>
          {(d) => (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Service</th><th>Health check</th><th className="num">Check latency</th>
                    <th className="num">p95 latency</th><th className="num">Error rate</th><th className="num">Requests</th>
                    <th className="num">Availability (24h)</th><th>Last check</th>
                  </tr>
                </thead>
                <tbody>
                  {d.map((o) => {
                    const s = o.service; const m = o.metrics;
                    const dep = s.nodeType === 'DEPENDENCY_ONLY';
                    return (
                      <tr key={s.id}>
                        <td>
                          <Link to={`/services/${s.id}`}>{s.displayName || s.name}</Link>{' '}
                          <DemoBadge demo={s.demo} />{' '}
                          {dep && <Badge tone="plain" title="Not health-checked; metrics come from caller measurements">dependency only</Badge>}
                          {!s.enabled && <> <Badge tone="plain">disabled</Badge></>}
                          <div className="muted small mono">{s.name}</div>
                        </td>
                        <td>{dep ? <span className="muted">n/a</span> : <HealthBadge status={o.health && o.health.status} />}</td>
                        <td className="num">{o.health ? formatMs(o.health.responseTimeMs) : '—'}</td>
                        <td className="num">{m ? formatMs(m.p95Ms) : '—'}</td>
                        <td className="num">{m ? formatRate(m.errorRate) : '—'}</td>
                        <td className="num">{m ? m.requestCount : '—'}</td>
                        <td className="num">{formatPercent(o.availability24h)}</td>
                        <td>{o.health ? formatTime(o.health.checkedAt) : '—'}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Async>
        <div className="muted small" style={{ marginTop: 8 }}>
          A passing health check does not imply healthy performance; compare with p95 latency and error rate.
          Metric columns show a dash when no telemetry arrived in the last 5 minutes.
        </div>
      </div>
    </>
  );
}
