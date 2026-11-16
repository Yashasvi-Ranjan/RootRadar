package com.rootradar.apifd.graph;

import com.rootradar.apifd.monitoring.NodeType;

/** Pure-graph node, identified by service name. */
public record GraphNode(String name, NodeType nodeType) {}
