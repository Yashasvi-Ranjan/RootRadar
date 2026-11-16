import { useCallback, useEffect, useRef, useState } from 'react';
import { useLiveRefresh } from './LiveProvider.jsx';

/**
 * Loads data via `fetcher(signal)`. Re-runs when `deps` change and, if `liveTypes` is given,
 * silently refreshes (no loading flash) on matching SSE events and after stream reconnects.
 */
export function useApi(fetcher, deps, liveTypes = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const fetcherRef = useRef(fetcher);
  useEffect(() => { fetcherRef.current = fetcher; });
  const seq = useRef(0);
  const ctrl = useRef(null);

  const run = useCallback((silent) => {
    if (ctrl.current) ctrl.current.abort();
    const c = new AbortController();
    ctrl.current = c;
    const mine = ++seq.current;
    if (!silent) setState((s) => ({ ...s, loading: true, error: null }));
    fetcherRef.current(c.signal).then(
      (data) => { if (mine === seq.current) setState({ data, error: null, loading: false }); },
      (error) => {
        if (error && error.name === 'AbortError') return;
        if (mine === seq.current) setState((s) => ({ data: silent ? s.data : null, error, loading: false }));
      },
    );
  }, []);

  // eslint-disable-next-line react-hooks/exhaustive-deps, react-hooks/set-state-in-effect
  useEffect(() => { run(false); return () => ctrl.current && ctrl.current.abort(); }, deps);

  useLiveRefresh(liveTypes.length ? liveTypes : ['stream.reconnected'], () => run(true));

  return { ...state, reload: () => run(false) };
}
