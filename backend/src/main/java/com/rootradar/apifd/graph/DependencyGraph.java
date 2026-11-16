package com.rootradar.apifd.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, validated, directed acyclic dependency graph. PURE: no Spring, no I/O; used directly by the RCA engine
 * and unit tests.
 *
 * <p>Direction: edge A -&gt; B means "A calls / depends on B". Therefore
 * <ul>
 *   <li>downstream(X) = everything X (transitively) depends on (callees), e.g. downstream(order) = {payment, database};</li>
 *   <li>upstream(X) = everything that (transitively) depends on X (callers), e.g. upstream(payment) = {order, gateway};</li>
 *   <li>the services AFFECTED if X fails = upstream(X).</li>
 * </ul>
 * Validation (construction and {@link #withEdge}): no self loops, endpoints must be known nodes, no duplicate edges,
 * no cycles. Result collections preserve insertion order and never include the queried node itself.
 */
public final class DependencyGraph {

    private final Map<String, GraphNode> nodes;
    private final Map<String, Set<String>> out;   // node -> direct downstream
    private final Map<String, Set<String>> in;    // node -> direct upstream
    private final List<GraphEdge> edges;

    private DependencyGraph(Map<String, GraphNode> nodes, List<GraphEdge> edges) {
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        Map<String, Set<String>> o = new LinkedHashMap<>();
        Map<String, Set<String>> i = new LinkedHashMap<>();
        nodes.keySet().forEach(n -> { o.put(n, new LinkedHashSet<>()); i.put(n, new LinkedHashSet<>()); });
        edges.forEach(e -> { o.get(e.from()).add(e.to()); i.get(e.to()).add(e.from()); });
        this.out = o;
        this.in = i;
        this.edges = List.copyOf(edges);
    }

    /** Builds and validates a graph. @throws InvalidGraphException on any violation. */
    public static DependencyGraph of(Collection<GraphNode> nodes, Collection<GraphEdge> edges) {
        Map<String, GraphNode> nodeMap = new LinkedHashMap<>();
        for (GraphNode n : nodes) nodeMap.put(n.name(), n);
        DependencyGraph g = new DependencyGraph(nodeMap, List.of());
        for (GraphEdge e : edges) g = g.withEdge(e.from(), e.to());
        return g;
    }

    public static DependencyGraph empty() {
        return new DependencyGraph(Map.of(), List.of());
    }

    /** Returns a NEW graph with the edge added after validation. @throws InvalidGraphException */
    public DependencyGraph withEdge(String from, String to) {
        if (from.equals(to)) {
            throw new InvalidGraphException(InvalidGraphException.Reason.SELF_LOOP, "Self loop not allowed: " + from);
        }
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
            throw new InvalidGraphException(InvalidGraphException.Reason.UNKNOWN_NODE,
                    "Unknown node in edge " + from + " -> " + to);
        }
        if (out.get(from).contains(to)) {
            throw new InvalidGraphException(InvalidGraphException.Reason.DUPLICATE_EDGE,
                    "Edge already exists: " + from + " -> " + to);
        }
        if (hasPath(to, from)) {
            throw new InvalidGraphException(InvalidGraphException.Reason.CYCLE,
                    "Edge " + from + " -> " + to + " would create a cycle");
        }
        List<GraphEdge> next = new ArrayList<>(edges);
        next.add(new GraphEdge(from, to));
        return new DependencyGraph(nodes, next);
    }

    public Collection<GraphNode> nodes() { return nodes.values(); }

    public List<GraphEdge> edges() { return edges; }

    public boolean contains(String name) { return nodes.containsKey(name); }

    /** Direct callees. Empty set for unknown names. */
    public Set<String> directDownstream(String name) {
        return Collections.unmodifiableSet(out.getOrDefault(name, Set.of()));
    }

    /** Direct callers. Empty set for unknown names. */
    public Set<String> directUpstream(String name) {
        return Collections.unmodifiableSet(in.getOrDefault(name, Set.of()));
    }

    /** All transitive callees, breadth-first order (nearest first). */
    public Set<String> downstream(String name) { return traverse(name, out); }

    /** All transitive callers, breadth-first order (nearest first). These are the services AFFECTED if {@code name} fails. */
    public Set<String> upstream(String name) { return traverse(name, in); }

    /** True if a directed path from -&gt; ... -&gt; to exists (false when from.equals(to)). */
    public boolean hasPath(String from, String to) {
        return !from.equals(to) && traverse(from, out).contains(to);
    }

    /** Number of hops of the shortest directed path from -&gt; to, or -1 if none. distance(x,x) = 0. */
    public int distance(String from, String to) {
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) return -1;
        if (from.equals(to)) return 0;
        Map<String, Integer> dist = new LinkedHashMap<>();
        Deque<String> q = new ArrayDeque<>();
        dist.put(from, 0);
        q.add(from);
        while (!q.isEmpty()) {
            String cur = q.poll();
            for (String nxt : out.get(cur)) {
                if (dist.containsKey(nxt)) continue;
                dist.put(nxt, dist.get(cur) + 1);
                if (nxt.equals(to)) return dist.get(nxt);
                q.add(nxt);
            }
        }
        return -1;
    }

    /** Nodes with no callers (entry points, e.g. gateway-service). */
    public List<String> roots() {
        return in.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
    }

    /** Nodes with no callees (e.g. database). */
    public List<String> leaves() {
        return out.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
    }

    /** Callers before callees (entry points first). */
    public List<String> topologicalOrder() {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        in.forEach((k, v) -> indeg.put(k, v.size()));
        Deque<String> q = new ArrayDeque<>();
        indeg.forEach((k, v) -> { if (v == 0) q.add(k); });
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            String cur = q.poll();
            order.add(cur);
            for (String nxt : out.get(cur)) {
                if (indeg.merge(nxt, -1, Integer::sum) == 0) q.add(nxt);
            }
        }
        return order;
    }

    private static Set<String> traverse(String start, Map<String, Set<String>> adj) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            String cur = q.poll();
            for (String nxt : adj.getOrDefault(cur, Set.of())) {
                if (!nxt.equals(start) && seen.add(nxt)) q.add(nxt);
            }
        }
        return seen;
    }
}
