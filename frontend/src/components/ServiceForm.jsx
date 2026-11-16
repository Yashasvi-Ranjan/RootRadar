import { useState } from 'react';
import { api, ApiError } from '../services/api.js';
import { Field } from './common.jsx';

const EMPTY = {
  name: '', displayName: '', nodeType: 'HTTP_SERVICE', baseUrl: '', healthEndpoint: '/health',
  telemetryEndpoint: '/internal/telemetry', checkIntervalSeconds: 10, timeoutMs: 2000, enabled: true, description: '',
};

function fromService(s) {
  return {
    name: s.name, displayName: s.displayName || '', nodeType: s.nodeType, baseUrl: s.baseUrl || '',
    healthEndpoint: s.healthEndpoint || '', telemetryEndpoint: s.telemetryEndpoint || '',
    checkIntervalSeconds: s.checkIntervalSeconds, timeoutMs: s.timeoutMs, enabled: s.enabled, description: s.description || '',
  };
}

function buildBody(v) {
  const dep = v.nodeType === 'DEPENDENCY_ONLY';
  const body = {
    name: v.name.trim(), displayName: v.displayName.trim() || null, nodeType: v.nodeType,
    enabled: v.enabled, description: v.description.trim() || null,
  };
  if (!dep) {
    body.baseUrl = v.baseUrl.trim();
    body.healthEndpoint = v.healthEndpoint.trim();
    body.telemetryEndpoint = v.telemetryEndpoint.trim() || null;
    body.checkIntervalSeconds = Number(v.checkIntervalSeconds);
    body.timeoutMs = Number(v.timeoutMs);
  }
  return body;
}

/** Register (service == null) or edit a service. Server-side validation errors are shown per field. */
export default function ServiceForm({ service, onSaved, onCancel }) {
  const editing = !!service;
  const [v, setV] = useState(editing ? fromService(service) : EMPTY);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k) => (e) => setV({ ...v, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });
  const dep = v.nodeType === 'DEPENDENCY_ONLY';
  const fe = error instanceof ApiError ? error.fieldErrors : {};
  const cls = (k) => (fe[k] ? 'invalid' : '');

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = buildBody(v);
      if (editing) { delete body.name; delete body.nodeType; } // identity fields are shown read-only
      const saved = editing ? await api.updateService(service.id, body) : await api.createService(body);
      onSaved(saved);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="panel" onSubmit={submit} noValidate>
      <h2>{editing ? `Edit ${service.name}` : 'Register service'}</h2>
      {error && (
        <div className="inline-error" role="alert">
          {error.message}{error.code ? ` (${error.code})` : ''}
          {error.details && error.details.length > 0 && (
            <ul className="plain">{error.details.map((d, i) => <li key={i}>{d.field ? `${d.field}: ` : ''}{d.message}</li>)}</ul>
          )}
        </div>
      )}
      <div className="form-grid">
        <Field id="sf-name" label="Name (unique, e.g. payment-service)" error={fe.name}>
          <input id="sf-name" className={cls('name')} value={v.name} onChange={set('name')} disabled={editing} required />
        </Field>
        <Field id="sf-display" label="Display name" error={fe.displayName}>
          <input id="sf-display" className={cls('displayName')} value={v.displayName} onChange={set('displayName')} />
        </Field>
        <Field id="sf-type" label="Node type" error={fe.nodeType}>
          <select id="sf-type" value={v.nodeType} onChange={set('nodeType')} disabled={editing}>
            <option value="HTTP_SERVICE">HTTP_SERVICE (health-checked)</option>
            <option value="DEPENDENCY_ONLY">DEPENDENCY_ONLY (e.g. database)</option>
          </select>
        </Field>
        {!dep && (
          <>
            <Field id="sf-url" label="Base URL (http/https, allow-listed host)" error={fe.baseUrl}>
              <input id="sf-url" className={cls('baseUrl')} value={v.baseUrl} onChange={set('baseUrl')} placeholder="http://localhost:8083" />
            </Field>
            <Field id="sf-health" label="Health endpoint" error={fe.healthEndpoint}>
              <input id="sf-health" className={cls('healthEndpoint')} value={v.healthEndpoint} onChange={set('healthEndpoint')} />
            </Field>
            <Field id="sf-tel" label="Telemetry endpoint (optional)" error={fe.telemetryEndpoint}>
              <input id="sf-tel" className={cls('telemetryEndpoint')} value={v.telemetryEndpoint} onChange={set('telemetryEndpoint')} />
            </Field>
            <Field id="sf-int" label="Check interval (seconds)" error={fe.checkIntervalSeconds}>
              <input id="sf-int" type="number" min="1" className={cls('checkIntervalSeconds')} value={v.checkIntervalSeconds} onChange={set('checkIntervalSeconds')} />
            </Field>
            <Field id="sf-to" label="Timeout (ms)" error={fe.timeoutMs}>
              <input id="sf-to" type="number" min="1" className={cls('timeoutMs')} value={v.timeoutMs} onChange={set('timeoutMs')} />
            </Field>
          </>
        )}
        <Field id="sf-desc" label="Description" error={fe.description}>
          <input id="sf-desc" value={v.description} onChange={set('description')} />
        </Field>
        <div style={{ alignSelf: 'end' }}>
          <label className="check"><input type="checkbox" checked={v.enabled} onChange={set('enabled')} /> Enabled</label>
        </div>
      </div>
      <div className="actions">
        <button type="submit" className="primary" disabled={busy}>{busy ? 'Saving…' : editing ? 'Save changes' : 'Register'}</button>
        <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>
      </div>
    </form>
  );
}
