import { useMemo, useState } from 'react';
import { api } from '../services/api.js';
import { useApi } from '../hooks/useApi.js';
import { Async, Field, Pagination } from '../components/common.jsx';
import { IncidentTable } from '../components/IncidentParts.jsx';
import { RANGES, rangeToParams } from '../utils/format.js';

const STATUSES = ['DETECTED', 'ACTIVE', 'RECOVERING', 'RESOLVED'];
const SEVERITIES = ['CRITICAL', 'WARNING'];

export default function Incidents() {
  const [status, setStatus] = useState('');
  const [severity, setSeverity] = useState('');
  const [service, setService] = useState('');
  const [rangeKey, setRangeKey] = useState('');
  const [anchor, setAnchor] = useState(() => new Date());
  const [page, setPage] = useState(0);
  const services = useApi((s) => api.listServices(s), []);
  const range = useMemo(() => (rangeKey ? rangeToParams(rangeKey, anchor) : null), [rangeKey, anchor]);

  const q = useApi((s) => api.listIncidents({
    status, severity, service, from: range && range.from, to: range && range.to, page, size: 15,
  }, s), [status, severity, service, rangeKey, anchor, page], ['incident.opened', 'incident.updated', 'incident.resolved']);

  const reset = (setter) => (e) => { setter(e.target.value); setPage(0); setAnchor(new Date()); };

  return (
    <>
      <div className="page-head"><div><h1>Incidents</h1><div className="muted">History of detected incidents, newest first.</div></div></div>
      <div className="panel filters">
        <Field id="f-status" label="Status">
          <select id="f-status" value={status} onChange={reset(setStatus)}>
            <option value="">All</option>{STATUSES.map((s) => <option key={s}>{s}</option>)}
          </select>
        </Field>
        <Field id="f-sev" label="Severity">
          <select id="f-sev" value={severity} onChange={reset(setSeverity)}>
            <option value="">All</option>{SEVERITIES.map((s) => <option key={s}>{s}</option>)}
          </select>
        </Field>
        <Field id="f-svc" label="Service (root cause or impacted)">
          <select id="f-svc" value={service} onChange={reset(setService)}>
            <option value="">All</option>
            {(services.data || []).map((o) => <option key={o.service.id} value={o.service.name}>{o.service.name}</option>)}
          </select>
        </Field>
        <Field id="f-range" label="Detected within">
          <select id="f-range" value={rangeKey} onChange={reset(setRangeKey)}>
            <option value="">Any time</option>{RANGES.map((r) => <option key={r.key} value={r.key}>{r.label}</option>)}
          </select>
        </Field>
      </div>
      <div className="panel">
        <Async q={q} isEmpty={(d) => !d.items || d.items.length === 0} empty="No incidents match the filters.">
          {(d) => (
            <>
              <IncidentTable incidents={d.items} />
              <Pagination page={d.page} totalPages={d.totalPages} totalItems={d.totalItems} onPage={setPage} />
            </>
          )}
        </Async>
      </div>
    </>
  );
}
