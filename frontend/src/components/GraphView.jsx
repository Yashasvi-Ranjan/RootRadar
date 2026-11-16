// SVG rendering of the GraphView payload (GET /api/graph). Layout: columns by `layer`,
// rows by order inside the layer. Health colours the node fill; incident overlay colours the outline.
const W = 150;
const H = 54;
const GAP_X = 90;
const GAP_Y = 34;
const PAD = 20;

const HEALTH_FILL = { UP: '#e7f3ec', DOWN: '#fbe9e7', DEGRADED: '#fbf0dc' };
const HEALTH_TEXT = { UP: '#2f7d4f', DOWN: '#b3261e', DEGRADED: '#a8630a' };

export function layoutGraph(graph) {
  const byLayer = new Map();
  for (const n of graph.nodes || []) {
    const l = Number.isFinite(n.layer) ? n.layer : 0;
    if (!byLayer.has(l)) byLayer.set(l, []);
    byLayer.get(l).push(n);
  }
  const layers = [...byLayer.keys()].sort((a, b) => a - b);
  const pos = new Map();
  let maxRows = 1;
  layers.forEach((l, col) => {
    const list = byLayer.get(l).sort((a, b) => a.name.localeCompare(b.name));
    maxRows = Math.max(maxRows, list.length);
    list.forEach((n, row) => pos.set(n.serviceId, { x: PAD + col * (W + GAP_X), y: PAD + row * (H + GAP_Y) }));
  });
  return {
    pos,
    width: PAD * 2 + layers.length * W + Math.max(0, layers.length - 1) * GAP_X,
    height: PAD * 2 + maxRows * H + Math.max(0, maxRows - 1) * GAP_Y,
  };
}

/**
 * props: graph (GraphView), rootCause {name}|null, impacted: Set<string> of names with active incidents,
 * selectedId, highlightIds: Set<number>|null (selected + related), onSelect(id|null)
 */
export default function GraphView({ graph, rootCause, impacted, selectedId, highlightIds, onSelect }) {
  const { pos, width, height } = layoutGraph(graph);
  const dim = (id) => highlightIds && !highlightIds.has(id);
  return (
    <>
      <svg className="graph-svg" viewBox={`0 0 ${width} ${height}`} style={{ minWidth: Math.min(width, 520) }} role="img" aria-label="Service dependency graph">
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill="#7b8794" />
          </marker>
          <marker id="arrow-hl" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill="#1f5fa8" />
          </marker>
        </defs>
        {(graph.edges || []).map((e) => {
          const a = pos.get(e.fromServiceId);
          const b = pos.get(e.toServiceId);
          if (!a || !b) return null;
          const hl = highlightIds && highlightIds.has(e.fromServiceId) && highlightIds.has(e.toServiceId);
          const x1 = a.x + W; const y1 = a.y + H / 2;
          const x2 = b.x; const y2 = b.y + H / 2;
          const mx = (x1 + x2) / 2;
          return (
            <path key={e.id} d={`M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`} fill="none"
              stroke={hl ? '#1f5fa8' : '#7b8794'} strokeWidth={hl ? 2 : 1.3}
              opacity={highlightIds && !hl ? 0.25 : 1} markerEnd={`url(#${hl ? 'arrow-hl' : 'arrow'})`} />
          );
        })}
        {(graph.nodes || []).map((n) => {
          const p = pos.get(n.serviceId);
          const isRoot = rootCause && rootCause.name === n.name;
          const isImpacted = impacted && impacted.has(n.name);
          const selected = selectedId === n.serviceId;
          const depOnly = n.nodeType === 'DEPENDENCY_ONLY';
          const stroke = isRoot ? '#b3261e' : isImpacted ? '#a8630a' : selected ? '#1f5fa8' : '#9aa5b1';
          const sw = isRoot ? 3 : isImpacted || selected ? 2.5 : 1.2;
          const status = depOnly ? 'N/A' : n.healthStatus || 'UNKNOWN';
          return (
            <g key={n.serviceId} className="graph-node" transform={`translate(${p.x},${p.y})`} opacity={dim(n.serviceId) ? 0.35 : 1}
              onClick={() => onSelect && onSelect(selected ? null : n.serviceId)} tabIndex={0} role="button"
              aria-label={`${n.displayName || n.name}, ${status}${isRoot ? ', suspected root cause' : ''}`}
              onKeyDown={(ev) => { if ((ev.key === 'Enter' || ev.key === ' ') && onSelect) { ev.preventDefault(); onSelect(selected ? null : n.serviceId); } }}>
              <rect width={W} height={H} rx="3" fill={HEALTH_FILL[n.healthStatus] || '#eceff2'} stroke={stroke} strokeWidth={sw}
                strokeDasharray={depOnly ? '5 3' : undefined} />
              <text x={W / 2} y="20" textAnchor="middle" fontSize="12" fontWeight="600" fill="#1f2933">{truncate(n.displayName || n.name, 22)}</text>
              <text x={W / 2} y="36" textAnchor="middle" fontSize="11" fill={HEALTH_TEXT[n.healthStatus] || '#5f6b7a'}>
                {depOnly ? 'dependency only' : status}{n.demo ? ' · demo' : ''}
              </text>
              {isRoot && <text x={W / 2} y="49" textAnchor="middle" fontSize="10" fontWeight="600" fill="#b3261e">suspected root cause</text>}
              {!isRoot && isImpacted && <text x={W / 2} y="49" textAnchor="middle" fontSize="10" fill="#a8630a">impacted</text>}
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span><i className="swatch" style={{ borderColor: '#9aa5b1', background: HEALTH_FILL.UP }} /> health UP</span>
        <span><i className="swatch" style={{ borderColor: '#9aa5b1', background: HEALTH_FILL.DOWN }} /> health DOWN</span>
        <span><i className="swatch" style={{ borderColor: '#9aa5b1', background: '#eceff2' }} /> unknown</span>
        <span><i className="swatch" style={{ borderColor: '#9aa5b1', borderStyle: 'dashed', background: '#eceff2' }} /> dependency-only node (not health-checked)</span>
        <span><i className="swatch" style={{ borderColor: '#b3261e' }} /> suspected root cause</span>
        <span><i className="swatch" style={{ borderColor: '#a8630a' }} /> impacted by active incident</span>
        <span>Arrow A to B: A depends on B</span>
      </div>
    </>
  );
}

function truncate(s, n) {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}
