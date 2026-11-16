import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../services/api.js';
import { useApi } from '../hooks/useApi.js';
import { Async, Badge, DemoBadge, ErrorState, HealthBadge, Loading } from '../components/common.jsx';
import GraphView from '../components/GraphView.jsx';
import { formatMs, formatRate } from '../utils/format.js';

export function incidentOverlay(incidents) {
  const impacted = new Set();
  let rootCause = null;
  for (const i of incidents || []) {
    (i.impactedServices || []).forEach((s) => impacted.add(s.name));
    if (i.rootCause && !rootCause) rootCause = i.rootCause;
    if (i.rootCause) impacted.add(i.rootCause.name);
  }
  return { impacted, rootCause };
}

export default function GraphPage() {
  const graph = useApi((s) => api.graph(s), [], ['health.updated', 'incident.opened', 'incident.resolved']);
  const incidents = useApi((s) => api.activeIncidents(s), [], ['incident.opened', 'incident.updated', 'incident.resolved']);
  const [selectedId, setSelectedId] = useState(null);
  const up = useApi((s) => (selectedId ? api.upstream(selectedId, s) : Promise.resolve(null)), [selectedId]);
  const down = useApi((s) => (selectedId ? api.downstream(selectedId, s) : Promise.resolve(null)), [selectedId]);
  const overlay = useMemo(() => incidentOverlay(incidents.data), [incidents.data]);

  const highlight = useMemo(() => {
    if (!selectedId) return null;
    const ids = new Set([selectedId]);
    (up.data ? up.data.services : []).forEach((x) => ids.add(x.id));
    (down.data ? down.data.services : []).forEach((x) => ids.add(x.id));
    return ids;
  }, [selectedId, up.data, down.data]);

  return (
    <>
      <div className="page-head">
        <div><h1>Dependency graph</h1><div className="muted">Arrow A to B means A calls / depends on B. Select a node to highlight its callers (upstream) and dependencies (downstream).</div></div>
      </div>
      <div className="grid-detail">
        <div className="panel" style={{ overflowX: 'auto' }}>
          <Async q={graph} isEmpty={(d) => !d.nodes || d.nodes.length === 0} empty="The graph has no nodes yet. Register services first.">
            {(g) => (
              <>
                {incidents.error && <div className="inline-error">Incident overlay unavailable: {incidents.error.message}</div>}
                <GraphView graph={g} rootCause={overlay.rootCause} impacted={overlay.impacted} selectedId={selectedId}
                  highlightIds={highlight} onSelect={setSelectedId} />
              </>
            )}
          </Async>
        </div>
        <div className="panel">
          <h2>Selection</h2>
          {!selectedId && <div className="muted">No node selected.</div>}
          {selectedId && graph.data && (() => {
            const n = graph.data.nodes.find((x) => x.serviceId === selectedId);
            if (!n) return <div className="muted">Node no longer exists.</div>;
            const m = n.recentMetrics;
            return (
              <>
                <h3><Link to={`/services/${n.serviceId}`}>{n.displayName || n.name}</Link> <DemoBadge demo={n.demo} /></h3>
                <div style={{ marginBottom: 8 }}>
                  {n.nodeType === 'DEPENDENCY_ONLY' ? <Badge tone="plain">dependency only: not health-checked</Badge> : <HealthBadge status={n.healthStatus} />}
                </div>
                {m && <div className="small muted">Recent: p95 {formatMs(m.p95Ms)}, errors {formatRate(m.errorRate)}, {m.requestCount} requests</div>}
                <h3 style={{ marginTop: 10 }}>Upstream (callers affected if it fails)</h3>
                {up.loading ? <Loading /> : up.error ? <ErrorState error={up.error} onRetry={up.reload} /> :
                  <ul className="plain">{up.data && up.data.services.length ? up.data.services.map((x) => <li key={x.id}>{x.name}</li>) : <li className="muted">none</li>}</ul>}
                <h3 style={{ marginTop: 10 }}>Downstream (dependencies)</h3>
                {down.loading ? <Loading /> : down.error ? <ErrorState error={down.error} onRetry={down.reload} /> :
                  <ul className="plain">{down.data && down.data.services.length ? down.data.services.map((x) => <li key={x.id}>{x.name}</li>) : <li className="muted">none</li>}</ul>}
              </>
            );
          })()}
        </div>
      </div>
    </>
  );
}
