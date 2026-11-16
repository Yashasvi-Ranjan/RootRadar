// REST client for the RootRadar backend (see docs/API_CONTRACTS.md).
// In dev, requests go to the same origin and Vite proxies /api. In a production build,
// VITE_API_BASE_URL (if set) is the backend origin.

const metaEnv = (typeof import.meta !== 'undefined' && import.meta.env) || {};
export const API_BASE = metaEnv.PROD ? (metaEnv.VITE_API_BASE_URL || '') : '';

export class ApiError extends Error {
  constructor({ status, code, message, details, path }) {
    super(message || `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code || null;
    this.details = Array.isArray(details) ? details : [];
    this.path = path || null;
  }

  /** {field: message} map built from ApiError.details. */
  get fieldErrors() {
    const out = {};
    for (const d of this.details) {
      if (d && d.field && !out[d.field]) out[d.field] = d.message;
    }
    return out;
  }
}

export function buildQuery(params) {
  const sp = new URLSearchParams();
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v === undefined || v === null || v === '') continue;
      if (Array.isArray(v)) {
        if (v.length) sp.set(k, v.join(','));
      } else {
        sp.set(k, String(v));
      }
    }
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export async function request(method, path, { params, body, signal, base = API_BASE, fetchImpl } = {}) {
  const doFetch = fetchImpl || globalThis.fetch.bind(globalThis);
  const headers = { Accept: 'application/json' };
  const init = { method, headers, signal };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await doFetch(`${base}${path}${buildQuery(params)}`, init);
  } catch (e) {
    if (e && e.name === 'AbortError') throw e;
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'Cannot reach the backend. Is it running?' });
  }
  if (res.status === 204) return null;
  let data = null;
  const text = await res.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) {
    throw new ApiError({
      status: res.status,
      code: data && data.code,
      message: (data && data.message) || res.statusText || `HTTP ${res.status}`,
      details: data && data.details,
      path: data && data.path,
    });
  }
  return data;
}

const get = (path, params, signal) => request('GET', path, { params, signal });

export const api = {
  systemInfo: (signal) => get('/api/system/info', null, signal),

  listServices: (signal) => get('/api/services', null, signal),
  getService: (id, signal) => get(`/api/services/${id}`, null, signal),
  createService: (body) => request('POST', '/api/services', { body }),
  updateService: (id, body) => request('PUT', `/api/services/${id}`, { body }),
  deleteService: (id) => request('DELETE', `/api/services/${id}`),
  checkService: (id) => request('POST', `/api/services/${id}/check`),
  serviceHealth: (id, params, signal) => get(`/api/services/${id}/health`, params, signal),
  serviceAvailability: (id, params, signal) => get(`/api/services/${id}/availability`, params, signal),
  serviceMetrics: (id, params, signal) => get(`/api/services/${id}/metrics`, params, signal),
  serviceEndpoints: (id, params, signal) => get(`/api/services/${id}/endpoints`, params, signal),
  dependencyMetrics: (id, params, signal) => get(`/api/services/${id}/dependencies/metrics`, params, signal),

  graph: (signal) => get('/api/graph', null, signal),
  upstream: (id, signal) => get(`/api/graph/services/${id}/upstream`, { transitive: true }, signal),
  downstream: (id, signal) => get(`/api/graph/services/${id}/downstream`, { transitive: true }, signal),
  impact: (id, signal) => get(`/api/graph/services/${id}/impact`, null, signal),

  listRules: (params, signal) => get('/api/detection-rules', params, signal),
  updateRule: (id, body) => request('PUT', `/api/detection-rules/${id}`, { body }),

  listIncidents: (params, signal) => get('/api/incidents', params, signal),
  activeIncidents: (signal) => get('/api/incidents/active', null, signal),
  getIncident: (id, signal) => get(`/api/incidents/${id}`, null, signal),
};
