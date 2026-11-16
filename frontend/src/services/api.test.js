import { describe, expect, it, vi } from 'vitest';
import { ApiError, buildQuery, request } from './api.js';

const resp = (status, body) => ({
  ok: status >= 200 && status < 300, status, statusText: 'x',
  text: async () => (body === undefined ? '' : JSON.stringify(body)),
});

describe('api client', () => {
  it('builds queries skipping empty values and joining arrays', () => {
    expect(buildQuery({ a: 1, b: '', c: null, d: undefined, e: ['X', 'Y'] })).toBe('?a=1&e=X%2CY');
    expect(buildQuery({})).toBe('');
  });

  it('sends JSON bodies and returns parsed data', async () => {
    const f = vi.fn().mockResolvedValue(resp(201, { id: 1 }));
    const out = await request('POST', '/api/services', { body: { name: 'a' }, fetchImpl: f, base: '' });
    expect(out).toEqual({ id: 1 });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe('/api/services');
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"name":"a"}');
  });

  it('returns null on 204', async () => {
    const f = vi.fn().mockResolvedValue(resp(204));
    expect(await request('DELETE', '/api/services/1', { fetchImpl: f, base: '' })).toBeNull();
  });

  it('maps backend ApiError bodies including field errors', async () => {
    const f = vi.fn().mockResolvedValue(resp(400, {
      status: 400, code: 'VALIDATION_FAILED', message: 'Request validation failed',
      details: [{ field: 'name', message: 'must not be blank' }],
    }));
    const err = await request('POST', '/api/services', { body: {}, fetchImpl: f, base: '' }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(400);
    expect(err.code).toBe('VALIDATION_FAILED');
    expect(err.fieldErrors).toEqual({ name: 'must not be blank' });
  });

  it('reports network failures as ApiError status 0', async () => {
    const f = vi.fn().mockRejectedValue(new TypeError('fetch failed'));
    const err = await request('GET', '/api/graph', { fetchImpl: f, base: '' }).catch((e) => e);
    expect(err.status).toBe(0);
    expect(err.code).toBe('NETWORK_ERROR');
  });
});
