package com.rootradar.apifd.graph;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.common.ServiceRef;
import com.rootradar.apifd.graph.persistence.ServiceDependencyEntity;
import com.rootradar.apifd.graph.persistence.ServiceDependencyRepository;
import com.rootradar.apifd.monitoring.HealthCheckResult;
import com.rootradar.apifd.monitoring.HealthQueryService;
import com.rootradar.apifd.monitoring.HealthStatus;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import com.rootradar.apifd.telemetry.MetricsQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence and validation of dependency edges. All structural rules (self loop, unknown node, duplicate, cycle)
 * are enforced by the pure {@link DependencyGraph#withEdge}; edge writes are serialised by a lock and run in their
 * own transaction so two concurrent additions cannot jointly create a cycle (single backend instance assumed).
 */
@Service
public class JpaDependencyGraphService implements DependencyGraphService {

    private final ServiceDependencyRepository edges;
    private final ServiceRegistry registry;
    private final HealthQueryService health;
    private final MetricsQueryService metrics;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ReentrantLock writeLock = new ReentrantLock();

    public JpaDependencyGraphService(ServiceDependencyRepository edges, ServiceRegistry registry,
                                     HealthQueryService health, MetricsQueryService metrics, Clock clock,
                                     PlatformTransactionManager txm) {
        this.edges = edges;
        this.registry = registry;
        this.health = health;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = new TransactionTemplate(txm);
    }

    @Override
    public DependencyGraph currentGraph() {
        List<MonitoredService> services = registry.findAll();
        return build(services, edges.findAll(Sort.by("id")), idToName(services));
    }

    @Override
    public List<GraphEdgeView> listEdges() {
        Map<Long, String> names = idToName(registry.findAll());
        return edges.findAll(Sort.by("id")).stream().map(e -> view(e, names)).toList();
    }

    @Override
    public GraphEdgeView addEdge(AddEdgeRequest r) {
        writeLock.lock();
        try {
            return tx.execute(status -> {
                if (r.fromServiceId().equals(r.toServiceId())) {
                    throw new ApiException.BadRequest("INVALID_GRAPH", "Self loop not allowed");
                }
                MonitoredService from = registry.findById(r.fromServiceId()).orElseThrow(
                        () -> new ApiException.NotFound("Service " + r.fromServiceId() + " not found"));
                MonitoredService to = registry.findById(r.toServiceId()).orElseThrow(
                        () -> new ApiException.NotFound("Service " + r.toServiceId() + " not found"));
                String measurement = r.measurementName() == null || r.measurementName().isBlank()
                        ? to.name() : r.measurementName().trim();
                List<MonitoredService> services = registry.findAll();
                try {
                    build(services, edges.findAll(Sort.by("id")), idToName(services)).withEdge(from.name(), to.name());
                } catch (InvalidGraphException e) {
                    throw map(e);
                }
                if (edges.existsByFromServiceIdAndMeasurementName(from.id(), measurement)) {
                    throw new ApiException.Conflict("CONFLICT", "'" + from.name()
                            + "' already has a dependency with measurementName '" + measurement + "'");
                }
                ServiceDependencyEntity saved = edges.saveAndFlush(
                        new ServiceDependencyEntity(from.id(), to.id(), measurement, clock.instant()));
                return new GraphEdgeView(saved.getId(), from.id(), from.name(), to.id(), to.name(), measurement);
            });
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void removeEdge(long edgeId) {
        writeLock.lock();
        try {
            tx.executeWithoutResult(status -> {
                if (!edges.existsById(edgeId)) throw new ApiException.NotFound("Edge " + edgeId + " not found");
                edges.deleteById(edgeId);
            });
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public GraphView view() {
        Instant now = clock.instant();
        List<MonitoredService> services = registry.findAll();
        Map<Long, String> names = idToName(services);
        List<ServiceDependencyEntity> rows = edges.findAll(Sort.by("id"));
        DependencyGraph g = build(services, rows, names);
        Map<String, Integer> layers = layers(g);
        Map<Long, HealthCheckResult> latest = health.latestForAll();
        List<GraphNodeView> nodes = new ArrayList<>();
        for (MonitoredService s : services) {
            HealthCheckResult h = latest.get(s.id());
            HealthStatus hs = s.nodeType() == NodeType.DEPENDENCY_ONLY || h == null ? HealthStatus.UNKNOWN : h.status();
            nodes.add(new GraphNodeView(s.id(), s.name(), s.displayName(), s.nodeType(), s.demo(),
                    layers.getOrDefault(s.name(), 0), hs,
                    metrics.serviceSummary(s.id(), now.minus(Duration.ofMinutes(5)), now).orElse(null)));
        }
        return new GraphView(now, nodes, rows.stream().map(e -> view(e, names)).toList());
    }

    @Override
    public TraversalView upstream(long serviceId, boolean transitive) {
        return traverse(serviceId, true, transitive, "UPSTREAM");
    }

    @Override
    public TraversalView downstream(long serviceId, boolean transitive) {
        return traverse(serviceId, false, transitive, "DOWNSTREAM");
    }

    @Override
    public TraversalView impact(long serviceId) {
        return traverse(serviceId, true, true, "UPSTREAM");
    }

    private TraversalView traverse(long serviceId, boolean up, boolean transitive, String direction) {
        List<MonitoredService> services = registry.findAll();
        MonitoredService origin = services.stream().filter(s -> s.id() == serviceId).findFirst()
                .orElseThrow(() -> new ApiException.NotFound("Service " + serviceId + " not found"));
        DependencyGraph g = build(services, edges.findAll(Sort.by("id")), idToName(services));
        Set<String> found = up
                ? (transitive ? g.upstream(origin.name()) : g.directUpstream(origin.name()))
                : (transitive ? g.downstream(origin.name()) : g.directDownstream(origin.name()));
        Map<String, MonitoredService> byName = services.stream()
                .collect(Collectors.toMap(MonitoredService::name, Function.identity()));
        List<ServiceRef> refs = found.stream().map(byName::get).map(s -> new ServiceRef(s.id(), s.name())).toList();
        return new TraversalView(new ServiceRef(origin.id(), origin.name()), direction, transitive, refs);
    }

    private static DependencyGraph build(List<MonitoredService> services, List<ServiceDependencyEntity> rows,
                                         Map<Long, String> names) {
        List<GraphNode> nodes = services.stream().map(s -> new GraphNode(s.name(), s.nodeType())).toList();
        List<GraphEdge> es = rows.stream()
                .map(e -> new GraphEdge(names.get(e.getFromServiceId()), names.get(e.getToServiceId()))).toList();
        return DependencyGraph.of(nodes, es);
    }

    /** Layer = length of the longest path from any entry point. */
    private static Map<String, Integer> layers(DependencyGraph g) {
        Map<String, Integer> layer = new HashMap<>();
        for (String n : g.topologicalOrder()) {
            int l = 0;
            for (String up : g.directUpstream(n)) l = Math.max(l, layer.getOrDefault(up, 0) + 1);
            layer.put(n, l);
        }
        return layer;
    }

    private static Map<Long, String> idToName(List<MonitoredService> services) {
        Map<Long, String> m = new LinkedHashMap<>();
        services.forEach(s -> m.put(s.id(), s.name()));
        return m;
    }

    private static GraphEdgeView view(ServiceDependencyEntity e, Map<Long, String> names) {
        return new GraphEdgeView(e.getId(), e.getFromServiceId(), names.get(e.getFromServiceId()),
                e.getToServiceId(), names.get(e.getToServiceId()), e.getMeasurementName());
    }

    private static ApiException map(InvalidGraphException e) {
        return switch (e.getReason()) {
            case SELF_LOOP, UNKNOWN_NODE -> new ApiException.BadRequest("INVALID_GRAPH", e.getMessage());
            case DUPLICATE_EDGE, CYCLE -> new ApiException.Conflict("INVALID_GRAPH", e.getMessage());
        };
    }
}
