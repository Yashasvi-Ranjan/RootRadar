import { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../services/api.js';
import { useApi } from '../hooks/useApi.js';
import { Async, Empty, IncidentStatusBadge, SeverityBadge } from '../components/common.jsx';
import { DiagnosisPanel, Timeline } from '../components/IncidentParts.jsx';
import GraphView from '../components/GraphView.jsx';
import { formatDateTime, formatDuration, formatMs } from '../utils/format.js';

const LIVE = ['incident.updated', 'incident.resolved', 'incident.opened', 'diagnosis.updated', 'anomaly.detected', 'anomaly.cleared'];

export default function IncidentDetail() {
  const { id } = useParams();
  const q = useApi((s) => api.getIncident(id, s), [id], LIVE);
  const graph = useApi((s) => api.graph(s), []);
  const impacted = useMemo(() => new Set(q.data ? q.data.incident.impactedServices.map((x) => x.name) : []), [q.data]);

  return (
    <Async q={q}>
      {(d) => {
        const i = d.incident;
        return (
          <>
            <div className="page-head">
              <div>
                <div className="small"><Link to="/incidents">Incidents</Link> /</div>
                <h1>#{i.id} {i.title}</h1>
                <div className="actions"><IncidentStatusBadge status={i.status} /> <SeverityBadge severity={i.severity} /></div>
              </div>
            </div>

            <div className="grid2">
              <div className="panel">
                <h2>Summary</h2>
                <dl className="kv">
                  <dt>Affected endpoint</dt><dd className="mono">{i.affectedEndpoint || '—'}</dd>
                  <dt>Suspected root cause</dt><dd>{i.rootCause ? <>{i.rootCause.name}{i.rootCauseScore != null && ` (ranking score ${i.rootCauseScore} / 100)`}</> : 'not determined'}</dd>
                  <dt>Root cause summary</dt><dd>{i.rootCauseSummary || '—'}</dd>
                  <dt>Impacted services</dt><dd>{i.impactedServices.length ? i.impactedServices.map((s) => s.name).join(', ') : '—'}</dd>
                  <dt>Detected</dt><dd>{formatDateTime(i.detectedAt)}</dd>
                  <dt>Activated</dt><dd>{formatDateTime(i.activatedAt)}</dd>
                  <dt>Recovering since</dt><dd>{formatDateTime(i.recoveringAt)}</dd>
                  <dt>Resolved</dt><dd>{formatDateTime(i.resolvedAt)}{i.resolutionReason ? ` (${i.resolutionReason})` : ''}</dd>
                  <dt>Duration</dt><dd>{formatDuration(i.durationSeconds)}</dd>
                  <dt>Reopened</dt><dd>{i.reopenCount} time(s)</dd>
                </dl>
              </div>
              <div className="panel">
                <h2>Dependency graph</h2>
                <Async q={graph} isEmpty={(g) => !g.nodes || !g.nodes.length} empty="Graph is empty.">
                  {(g) => <GraphView graph={g} rootCause={i.rootCause} impacted={impacted} />}
                </Async>
                <div className="muted small" style={{ marginTop: 6 }}>Health colours show current state; outlines show this incident&apos;s root cause and impacted services.</div>
              </div>
            </div>

            <div className="panel">
              <h2>Diagnosis (root cause ranking)</h2>
              <DiagnosisPanel diagnosis={d.latestDiagnosis} />
            </div>

            <div className="grid2">
              <div className="panel"><h2>Timeline</h2><Timeline entries={d.timeline} /></div>
              <div className="panel">
                <h2>Anomalies</h2>
                {!d.anomalies || d.anomalies.length === 0 ? <Empty>No anomalies linked.</Empty> : (
                  <div className="table-wrap"><table>
                    <thead><tr><th>Type</th><th>Subject</th><th>Severity</th><th className="num">Observed</th><th className="num">Baseline</th><th>State</th></tr></thead>
                    <tbody>{d.anomalies.map((a) => (
                      <tr key={a.id}>
                        <td>{a.type}<div className="muted small">{a.detectionMethod}</div></td>
                        <td>{a.subjectService}{a.dependencyName ? ` to ${a.dependencyName}` : ''}{a.endpoint ? <div className="mono small">{a.method || ''} {a.endpoint}</div> : null}</td>
                        <td><SeverityBadge severity={a.severity} /></td>
                        <td className="num">{a.type === 'ERROR_RATE' ? a.observedValue : formatMs(a.observedValue)}</td>
                        <td className="num">{a.baselineValue == null ? '—' : a.type === 'ERROR_RATE' ? a.baselineValue : formatMs(a.baselineValue)}</td>
                        <td>{a.status}<div className="muted small">{formatDateTime(a.detectedAt)}</div></td>
                      </tr>
                    ))}</tbody>
                  </table></div>
                )}
                {d.anomalies && d.anomalies.length > 0 && (
                  <ul className="plain small muted">{d.anomalies.map((a) => <li key={a.id}>{a.description}</li>)}</ul>
                )}
              </div>
            </div>
          </>
        );
      }}
    </Async>
  );
}
