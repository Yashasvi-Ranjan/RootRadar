package com.rootradar.apifd.graph;

/** Persisted edge as returned by the API. */
public record GraphEdgeView(long id, long fromServiceId, String fromService, long toServiceId, String toService,
                            String measurementName) {}
