import { beforeEach, describe, expect, it, vi } from 'vitest';
import { connectStream } from './sse.js';

let instances;
class FakeES {
  constructor(url) { this.url = url; this.listeners = {}; this.closed = false; instances.push(this); }
  addEventListener(n, fn) { this.listeners[n] = fn; }
  close() { this.closed = true; }
}

describe('connectStream', () => {
  beforeEach(() => { instances = []; vi.useFakeTimers(); });

  const make = (extra = {}) => {
    const statuses = []; const events = []; const onReconnect = vi.fn();
    const conn = connectStream({
      url: '/api/stream', EventSourceImpl: FakeES, onStatus: (s) => statuses.push(s),
      onEvent: (e) => events.push(e), onReconnect, ...extra,
    });
    return { conn, statuses, events, onReconnect };
  };

  it('unwraps the envelope and delivers events', () => {
    const { events, statuses } = make();
    instances[0].onopen();
    instances[0].listeners['incident.opened']({ data: JSON.stringify({ type: 'incident.opened', data: { change: 'OPENED' } }) });
    expect(statuses).toEqual(['connecting', 'connected']);
    expect(events[0]).toMatchObject({ type: 'incident.opened', data: { change: 'OPENED' } });
  });

  it('ignores malformed frames', () => {
    const { events } = make();
    instances[0].listeners['health.updated']({ data: 'not json' });
    expect(events).toHaveLength(0);
  });

  it('reconnects with backoff after an error and signals a refetch', () => {
    const { statuses, onReconnect } = make();
    instances[0].onopen();
    expect(onReconnect).not.toHaveBeenCalled(); // first open is not a reconnect
    instances[0].onerror();
    expect(instances[0].closed).toBe(true);
    expect(statuses.at(-1)).toBe('disconnected');
    vi.advanceTimersByTime(999);
    expect(instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(instances).toHaveLength(2);
    instances[1].onerror(); // second consecutive failure: 2 s delay
    vi.advanceTimersByTime(1999);
    expect(instances).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(instances).toHaveLength(3);
    instances[2].onopen();
    expect(statuses.at(-1)).toBe('connected');
    expect(onReconnect).toHaveBeenCalledTimes(1);
  });

  it('stops reconnecting after close()', () => {
    const { conn } = make();
    instances[0].onerror();
    conn.close();
    vi.advanceTimersByTime(60000);
    expect(instances).toHaveLength(1);
  });
});
