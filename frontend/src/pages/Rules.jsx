import { useState } from 'react';
import { api, ApiError } from '../services/api.js';
import { useApi } from '../hooks/useApi.js';
import { Async, Badge } from '../components/common.jsx';

const EDITABLE = [
  'warningThreshold', 'criticalThreshold', 'baselineRatio', 'zScoreThreshold', 'minRequests',
  'consecutiveWindows', 'criticalConsecutiveWindows', 'baselineWindows', 'minBaselineWindows', 'recoveryWindows',
];

function RuleEditor({ rule, onDone }) {
  const [v, setV] = useState(() => ({ enabled: rule.enabled, ...Object.fromEntries(EDITABLE.map((k) => [k, rule[k] ?? ''])) }));
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);
  const fe = err instanceof ApiError ? err.fieldErrors : {};
  const save = async (e) => {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const body = { enabled: v.enabled };
      for (const k of EDITABLE) body[k] = v[k] === '' ? null : Number(v[k]);
      await api.updateRule(rule.id, body);
      onDone(true);
    } catch (x) { setErr(x); } finally { setBusy(false); }
  };
  return (
    <form className="panel" onSubmit={save}>
      <h3>Edit {rule.ruleKey}{rule.serviceName ? ` (${rule.serviceName})` : ' (global)'}</h3>
      {err && <div className="inline-error" role="alert">{err.message}{err.details && err.details.length > 0 && <ul className="plain">{err.details.map((d, i) => <li key={i}>{d.field}: {d.message}</li>)}</ul>}</div>}
      <div className="form-grid">
        {EDITABLE.map((k) => (
          <div key={k}>
            <label htmlFor={`r-${k}`}>{k}</label>
            <input id={`r-${k}`} type="number" step="any" className={fe[k] ? 'invalid' : ''} value={v[k]} onChange={(e) => setV({ ...v, [k]: e.target.value })} />
            {fe[k] && <div className="field-error">{fe[k]}</div>}
          </div>
        ))}
        <label className="check" style={{ alignSelf: 'end' }}><input type="checkbox" checked={v.enabled} onChange={(e) => setV({ ...v, enabled: e.target.checked })} /> Enabled</label>
      </div>
      <div className="actions">
        <button className="primary" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
        <button type="button" onClick={() => onDone(false)} disabled={busy}>Cancel</button>
      </div>
    </form>
  );
}

export default function Rules() {
  const q = useApi((s) => api.listRules(null, s), []);
  const [editing, setEditing] = useState(null);
  return (
    <>
      <div className="page-head"><div><h1>Detection rules</h1>
        <div className="muted">Thresholds used by anomaly detection. Changes apply on the next detection run, no restart needed.</div></div></div>
      {editing && <RuleEditor key={editing.id} rule={editing} onDone={(saved) => { setEditing(null); if (saved) q.reload(); }} />}
      <div className="panel">
        <Async q={q} isEmpty={(d) => d.length === 0} empty="No detection rules defined.">
          {(d) => (
            <div className="table-wrap"><table>
              <thead><tr><th>Rule</th><th>Metric</th><th>Scope</th><th className="num">Warning</th><th className="num">Critical</th>
                <th className="num">Baseline ratio</th><th className="num">z-score</th><th className="num">Consecutive</th><th className="num">Min requests</th><th>State</th><th /></tr></thead>
              <tbody>{d.map((r) => (
                <tr key={r.id}>
                  <td className="mono">{r.ruleKey}</td><td>{r.metric}</td><td>{r.serviceName || 'global'}</td>
                  <td className="num">{r.warningThreshold ?? '—'}</td><td className="num">{r.criticalThreshold ?? '—'}</td>
                  <td className="num">{r.baselineRatio ?? '—'}</td><td className="num">{r.zScoreThreshold ?? '—'}</td>
                  <td className="num">{r.consecutiveWindows}</td><td className="num">{r.minRequests}</td>
                  <td>{r.enabled ? 'enabled' : <Badge tone="plain">disabled</Badge>}</td>
                  <td><button onClick={() => setEditing(r)}>Edit</button></td>
                </tr>
              ))}</tbody>
            </table></div>
          )}
        </Async>
      </div>
    </>
  );
}
