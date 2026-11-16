package com.rootradar.apifd.graph;

import java.time.Instant;
import java.util.List;

/** GET /api/graph payload: everything the frontend needs to draw the dependency graph. */
public record GraphView(Instant generatedAt, List<GraphNodeView> nodes, List<GraphEdgeView> edges) {}
