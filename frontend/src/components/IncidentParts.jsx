import { Link } from 'react-router-dom';
import { ClassificationBadge, IncidentStatusBadge, SeverityBadge, Empty } from './common.jsx';
import { formatDateTime, formatDuration } from '../utils/format.js';

export function IncidentTable({ incidents }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Incident</th><th>Status</th><th>Severity</th><th>Affected endpoint</th>
            <th>Suspected root cause</th><th>Detected</th><th>Duration</th>
          </tr>
        </thead>
        <tbody>
          {incidents.map((i) => (
            <tr key={i.id}>
              <td><Link to={`/incidents/${i.id}`}>#{i.id} {i.title}</Link></td>
              <td><IncidentStatusBadge status={i.status} /></td>
              <td><SeverityBadge severity={i.severity} /></td>
              <td className="mono">{i.affectedEndpoint || '—'}</td>
              <td>{i.rootCause ? <>{i.rootCause.name}{i.rootCauseScore != null && <span className="muted small"> (score {i.rootCauseScore}/100)</span>}</> : <span className="muted">not determined</span>}</td>
              <td>{formatDateTime(i.detectedAt)}</td>
              <td>{formatDuration(i.durationSeconds)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Timeline({ entries }) {
  if (!entries || !entries.length) return <Empty>No timeline entries.</Empty>;
  const sorted = [...entries].sort((a, b) => new Date(a.occurredAt) - new Date(b.occurredAt));
  return (
    <ul className="timeline">
      {sorted.map((t) => (
        <li key={t.id}>
          <div><strong>{t.title}</strong> <span className="muted small">{t.eventType}</span></div>
          <div className="muted small">
            {formatDateTime(t.occurredAt)}
            {t.service && ` · ${t.service.name}`}
            {t.fromStatus && t.toStatus && ` · ${t.fromStatus} to ${t.toStatus}`}
          </div>
          {t.description && <div>{t.description}</div>}
        </li>
      ))}
    </ul>
  );
}

export function DiagnosisPanel({ diagnosis }) {
  if (!diagnosis) {
    return <Empty>No diagnosis has been produced for this incident yet.</Empty>;
  }
  return (
    <div>
      <div className="note">
        Scores are an explainable ranking score out of 100 built from the rule points listed below.
        They are <strong>not probabilities</strong> and do not prove causation.
      </div>
      <dl className="kv" style={{ marginBottom: 12 }}>
        <dt>Probable cause</dt><dd>{diagnosis.probableCauseService || 'none identified'}</dd>
        <dt>Top ranking score</dt><dd>{diagnosis.topScore} / 100 {diagnosis.conclusive ? '(conclusive)' : '(not conclusive)'}</dd>
        <dt>Analysed at</dt><dd>{formatDateTime(diagnosis.analyzedAt)} · model {diagnosis.modelVersion}</dd>
        <dt>Summary</dt><dd>{diagnosis.summary}</dd>
      </dl>
      {(diagnosis.findings || []).map((f) => (
        <div key={f.serviceName} className={`finding ${f.rank === 1 ? 'top' : ''}`}>
          <header>
            <strong>#{f.rank} {f.serviceName}</strong>
            <ClassificationBadge value={f.classification} />
            <span style={{ flex: 1, minWidth: 100 }}>
              <div className="scorebar"><span style={{ width: `${Math.max(0, Math.min(100, f.score))}%` }} /></div>
            </span>
            <span>ranking score {f.score} / 100</span>
          </header>
          <div className="body">
            {f.firstAbnormalAt && <div className="muted small">First abnormal: {formatDateTime(f.firstAbnormalAt)}</div>}
            <h3 style={{ marginTop: 8 }}>Score breakdown</h3>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Rule</th><th className="num">Points</th><th>Detail</th></tr></thead>
                <tbody>
                  {(f.factors || []).map((x) => (
                    <tr key={x.ruleKey} className={x.fired ? '' : 'muted'}>
                      <td>{x.label}{!x.fired && <span className="small"> (did not fire)</span>}</td>
                      <td className="num">{x.points} / {x.maxPoints}</td>
                      <td>{x.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {f.evidence && f.evidence.length > 0 && (
              <>
                <h3 style={{ marginTop: 10 }}>Evidence</h3>
                <ul className="plain">{f.evidence.map((e, i) => <li key={i}>{e}</li>)}</ul>
              </>
            )}
          </div>
        </div>
      ))}
      {diagnosis.caveats && diagnosis.caveats.length > 0 && (
        <div className="muted small">
          <strong>Caveats:</strong>
          <ul className="plain">{diagnosis.caveats.map((c, i) => <li key={i}>{c}</li>)}</ul>
        </div>
      )}
    </div>
  );
}
