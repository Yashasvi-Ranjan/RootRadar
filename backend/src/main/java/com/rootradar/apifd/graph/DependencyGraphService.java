package com.rootradar.apifd.graph;

import java.util.List;

/**
 * Persistence + validation facade over service_dependencies. Implemented by Agent 2 (bean {@code JpaDependencyGraphService}).
 *
 * <p>Error mapping: self loop / unknown node -> BadRequest("INVALID_GRAPH") (400); duplicate edge or cycle ->
 * Conflict("INVALID_GRAPH") (409); unknown service id -> NotFound (404). Validation is delegated to
 * {@link DependencyGraph#withEdge}. Traversal methods never mutate state.
 */
public interface DependencyGraphService {

    /** Snapshot of all nodes (enabled or not) and edges as a pure graph. Cheap enough to call per pipeline run. */
    DependencyGraph currentGraph();

    List<GraphEdgeView> listEdges();

    GraphEdgeView addEdge(AddEdgeRequest request);

    void removeEdge(long edgeId);

    /** Visualisation payload (health + last-5-min metrics per node). */
    GraphView view();

    /** Direct (transitive=false) or transitive callers of the node. */
    TraversalView upstream(long serviceId, boolean transitive);

    /** Direct or transitive callees of the node. */
    TraversalView downstream(long serviceId, boolean transitive);

    /** Services affected if the node fails (= transitive upstream). */
    TraversalView impact(long serviceId);
}
