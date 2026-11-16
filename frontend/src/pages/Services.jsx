import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { api } from '../services/api.js';
import { useApi } from '../hooks/useApi.js';
import { Async, Badge, DemoBadge, HealthBadge } from '../components/common.jsx';
import ServiceForm from '../components/ServiceForm.jsx';
import { formatMs } from '../utils/format.js';

export default function Services() {
  const q = useApi((s) => api.listServices(s), [], ['health.updated']);
  const [form, setForm] = useState(null); // null | 'new' | service
  const [actionError, setActionError] = useState(null);
  const [notice, setNotice] = useState(null);
  const [busyId, setBusyId] = useState(null);

  const saved = (s) => { setForm(null); setNotice(`Saved ${s.name}.`); q.reload(); };

  const remove = async (s) => {
    if (!window.confirm(`Delete ${s.name} and its history? This cannot be undone.`)) return;
    setBusyId(s.id); setActionError(null); setNotice(null);
    try {
      await api.deleteService(s.id);
      setNotice(`Deleted ${s.name}.`);
      q.reload();
    } catch (e) {
      setActionError(e);
    } finally { setBusyId(null); }
  };

  const check = async (s) => {
    setBusyId(s.id); setActionError(null); setNotice(null);
    try {
      const r = await api.checkService(s.id);
      setNotice(`${s.name}: ${r.status} (${r.outcome}${r.responseTimeMs != null ? `, ${formatMs(r.responseTimeMs)}` : ''})`);
      q.reload();
    } catch (e) {
      setActionError(e);
    } finally { setBusyId(null); }
  };

  return (
    <>
      <div className="page-head">
        <div><h1>Services</h1><div className="muted">Monitored services and dependency nodes.</div></div>
        <button className="primary" onClick={() => { setForm('new'); setNotice(null); }}><Plus size={14} /> Register service</button>
      </div>
      {notice && <div className="note">{notice}</div>}
      {actionError && <div className="inline-error" role="alert">{actionError.message}{actionError.code ? ` (${actionError.code})` : ''}</div>}
      {form && (
        <ServiceForm key={form === 'new' ? 'new' : form.id} service={form === 'new' ? null : form}
          onSaved={saved} onCancel={() => setForm(null)} />
      )}
      <div className="panel">
        <Async q={q} isEmpty={(d) => d.length === 0} empty="No services registered yet.">
          {(d) => (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr><th>Service</th><th>Type</th><th>Base URL</th><th>Health</th><th className="num">Interval</th>
                    <th className="num">Timeout</th><th>State</th><th /></tr>
                </thead>
                <tbody>
                  {d.map((o) => {
                    const s = o.service; const dep = s.nodeType === 'DEPENDENCY_ONLY';
                    return (
                      <tr key={s.id}>
                        <td><Link to={`/services/${s.id}`}>{s.displayName || s.name}</Link> <DemoBadge demo={s.demo} />
                          <div className="muted small mono">{s.name}</div></td>
                        <td>{dep ? <Badge tone="plain">dependency only</Badge> : 'HTTP service'}</td>
                        <td className="mono">{s.baseUrl || '—'}{s.healthEndpoint || ''}</td>
                        <td>{dep ? <span className="muted">n/a</span> : <HealthBadge status={o.health && o.health.status} />}</td>
                        <td className="num">{dep ? '—' : `${s.checkIntervalSeconds}s`}</td>
                        <td className="num">{dep ? '—' : `${s.timeoutMs} ms`}</td>
                        <td>{s.enabled ? 'enabled' : <Badge tone="plain">disabled</Badge>}</td>
                        <td>
                          <div className="actions">
                            {!dep && <button disabled={busyId === s.id} onClick={() => check(s)}>Check now</button>}
                            <button onClick={() => { setForm(s); setNotice(null); }}>Edit</button>
                            <button className="danger" disabled={busyId === s.id} onClick={() => remove(s)}>Delete</button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Async>
      </div>
    </>
  );
}
