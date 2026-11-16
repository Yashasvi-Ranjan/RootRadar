import { NavLink, Outlet } from 'react-router-dom';
import { Activity, Server, Network, AlertTriangle, SlidersHorizontal } from 'lucide-react';
import { useLive } from '../hooks/LiveProvider.jsx';
import { useApi } from '../hooks/useApi.js';
import { api } from '../services/api.js';
import { formatTime } from '../utils/format.js';

function LiveIndicator() {
  const { status, lastEventAt } = useLive();
  const tone = status === 'connected' ? 'ok' : status === 'connecting' ? 'warn' : 'bad';
  const text = status === 'connected' ? 'Live' : status === 'connecting' ? 'Connecting…' : 'Disconnected, retrying';
  return (
    <span className="live" role="status" title="Server-sent events from /api/stream">
      <span className={`dot ${tone}`} /> {text}
      {lastEventAt && status === 'connected' && <span className="muted"> · last event {formatTime(lastEventAt.toISOString())}</span>}
    </span>
  );
}

export default function Layout() {
  const { status } = useLive();
  const info = useApi((s) => api.systemInfo(s), []);
  const demoMode = info.data && info.data.demoMode;
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">RootRadar</div>
        <nav className="nav">
          <NavLink to="/" end><Activity size={15} /> Overview</NavLink>
          <NavLink to="/services"><Server size={15} /> Services</NavLink>
          <NavLink to="/graph"><Network size={15} /> Dependency graph</NavLink>
          <NavLink to="/incidents"><AlertTriangle size={15} /> Incidents</NavLink>
          <NavLink to="/rules"><SlidersHorizontal size={15} /> Detection rules</NavLink>
        </nav>
      </aside>
      <div className="main">
        <div className="topbar">
          <span className="muted small">
            API failure diagnosis
            {info.data && ` · backend v${info.data.version}`}
            {info.error && ' · backend info unavailable'}
          </span>
          <LiveIndicator />
        </div>
        {status === 'disconnected' && (
          <div className="banner bad" role="alert">
            Live updates are disconnected. Data shown may be stale; reconnecting automatically.
          </div>
        )}
        {demoMode && (
          <div className="banner demo">
            Demo mode: services flagged &quot;demo&quot; are fault-injectable demo components, not production systems.
          </div>
        )}
        <main className="content"><Outlet /></main>
      </div>
    </div>
  );
}
