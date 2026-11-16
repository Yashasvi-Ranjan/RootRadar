import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { connectStream } from '../services/sse.js';
import { API_BASE } from '../services/api.js';

const LiveContext = createContext(null);

/** Owns the single /api/stream connection; components subscribe to events via useLiveRefresh. */
export function LiveProvider({ children }) {
  const [status, setStatus] = useState('connecting');
  const [lastEventAt, setLastEventAt] = useState(null);
  const listeners = useRef(new Set());

  useEffect(() => {
    const emit = (evt) => listeners.current.forEach((fn) => fn(evt));
    const conn = connectStream({
      url: `${API_BASE}/api/stream`,
      onStatus: setStatus,
      onEvent: (evt) => {
        setLastEventAt(new Date());
        emit(evt);
      },
      // No replay on the server: after a reconnect REST is the source of truth.
      onReconnect: () => emit({ type: 'stream.reconnected', data: null }),
    });
    return () => conn.close();
  }, []);

  const value = useMemo(() => ({
    status,
    lastEventAt,
    subscribe: (fn) => {
      listeners.current.add(fn);
      return () => listeners.current.delete(fn);
    },
  }), [status, lastEventAt]);

  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}

export function useLive() {
  const ctx = useContext(LiveContext);
  if (!ctx) throw new Error('useLive must be used inside LiveProvider');
  return ctx;
}

/** Calls `callback` (debounced) when an event whose type is in `types` arrives. '*' matches all but heartbeat. */
export function useLiveRefresh(types, callback, debounceMs = 800) {
  const { subscribe } = useLive();
  const cb = useRef(callback);
  useEffect(() => { cb.current = callback; });
  const key = types.join('|');
  useEffect(() => {
    let timer = null;
    const set = new Set(key.split('|'));
    const off = subscribe((evt) => {
      if (evt.type === 'heartbeat') return;
      if (!(set.has('*') || set.has(evt.type) || evt.type === 'stream.reconnected')) return;
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => cb.current(evt), debounceMs);
    });
    return () => {
      off();
      if (timer) clearTimeout(timer);
    };
  }, [subscribe, key, debounceMs]);
}
