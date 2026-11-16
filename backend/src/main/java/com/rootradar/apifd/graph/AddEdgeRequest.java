package com.rootradar.apifd.graph;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/graph/edges. measurementName: the dependency name the CALLER reports in its telemetry; defaults to the
 * target node's name when null.
 */
public record AddEdgeRequest(@NotNull Long fromServiceId, @NotNull Long toServiceId,
                             @Size(max = 100) String measurementName) {}
