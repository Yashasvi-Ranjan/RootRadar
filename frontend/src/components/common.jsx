import { classLabel } from '../utils/format.js';

const HEALTH_TONE = { UP: 'ok', DOWN: 'bad', DEGRADED: 'warn' };
const SEVERITY_TONE = { CRITICAL: 'bad', HIGH: 'bad', MAJOR: 'bad', WARNING: 'warn', MEDIUM: 'warn', MINOR: 'warn', LOW: 'unknown', INFO: 'unknown' };
const INCIDENT_TONE = { DETECTED: 'warn', ACTIVE: 'bad', RECOVERING: 'warn', RESOLVED: 'ok' };
const CLASS_TONE = { PROBABLE_CAUSE: 'bad', CONTRIBUTING_FACTOR: 'warn', AFFECTED: 'unknown', INSUFFICIENT_EVIDENCE: 'plain' };

export function Badge({ tone = 'unknown', children, title }) {
  return <span className={`badge ${tone}`} title={title}>{children}</span>;
}

export function HealthBadge({ status }) {
  if (!status) return <Badge tone="unknown">NOT CHECKED</Badge>;
  return <Badge tone={HEALTH_TONE[status] || 'unknown'}>{status}</Badge>;
}
export function SeverityBadge({ severity }) {
  return <Badge tone={SEVERITY_TONE[severity] || 'unknown'}>{severity || '—'}</Badge>;
}
export function IncidentStatusBadge({ status }) {
  return <Badge tone={INCIDENT_TONE[status] || 'unknown'}>{status}</Badge>;
}
export function ClassificationBadge({ value }) {
  return <Badge tone={CLASS_TONE[value] || 'unknown'}>{classLabel(value).toUpperCase()}</Badge>;
}
/** Shown only when the backend flags the record as demo. */
export function DemoBadge({ demo }) {
  if (!demo) return null;
  return <Badge tone="demo" title="Demo environment service (fault-injectable), not a real production service">demo</Badge>;
}

export function Loading({ label = 'Loading…' }) {
  return <div className="state" role="status">{label}</div>;
}
export function Empty({ children = 'No data.' }) {
  return <div className="state">{children}</div>;
}
export function ErrorState({ error, onRetry }) {
  return (
    <div className="state error" role="alert">
      <div>{error && error.message ? error.message : 'Something went wrong.'}</div>
      {error && error.code && <div className="small">{error.code}{error.status ? ` (HTTP ${error.status})` : ''}</div>}
      {onRetry && <div style={{ marginTop: 8 }}><button onClick={onRetry}>Retry</button></div>}
    </div>
  );
}

/** Renders loading / error / empty / content for a useApi result. */
export function Async({ q, isEmpty, empty, children }) {
  if (q.loading && !q.data) return <Loading />;
  if (q.error && !q.data) return <ErrorState error={q.error} onRetry={q.reload} />;
  if (q.data && isEmpty && isEmpty(q.data)) return <Empty>{empty}</Empty>;
  if (!q.data) return <Loading />;
  return (
    <>
      {q.error && <div className="inline-error">Refresh failed: {q.error.message}</div>}
      {children(q.data)}
    </>
  );
}

export function Pagination({ page, totalPages, totalItems, onPage }) {
  if (!totalPages || totalPages <= 1) {
    return totalItems != null ? <div className="pager">{totalItems} item{totalItems === 1 ? '' : 's'}</div> : null;
  }
  return (
    <div className="pager">
      <span>{totalItems} items</span>
      <button disabled={page <= 0} onClick={() => onPage(page - 1)}>Previous</button>
      <span>Page {page + 1} of {totalPages}</span>
      <button disabled={page >= totalPages - 1} onClick={() => onPage(page + 1)}>Next</button>
    </div>
  );
}

export function RangeSelect({ value, onChange, ranges }) {
  return (
    <div>
      <label htmlFor="range">Time range</label>
      <select id="range" value={value} onChange={(e) => onChange(e.target.value)}>
        {ranges.map((r) => <option key={r.key} value={r.key}>{r.label}</option>)}
      </select>
    </div>
  );
}

export function Field({ id, label, error, children }) {
  return (
    <div>
      <label htmlFor={id}>{label}</label>
      {children}
      {error && <div className="field-error">{error}</div>}
    </div>
  );
}
