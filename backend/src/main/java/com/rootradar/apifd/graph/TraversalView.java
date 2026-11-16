package com.rootradar.apifd.graph;

import com.rootradar.apifd.common.ServiceRef;
import java.util.List;

/**
 * Result of upstream/downstream/impact queries. services are ordered nearest first.
 * direction: UPSTREAM (callers) | DOWNSTREAM (callees); for /impact the direction is UPSTREAM and the list is the
 * set of services affected if {@code origin} fails.
 */
public record TraversalView(ServiceRef origin, String direction, boolean transitive, List<ServiceRef> services) {}
