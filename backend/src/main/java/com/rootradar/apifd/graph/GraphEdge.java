package com.rootradar.apifd.graph;

/** Directed edge: {@code from} CALLS / DEPENDS ON {@code to}. Pure value (no ids). */
public record GraphEdge(String from, String to) {}
