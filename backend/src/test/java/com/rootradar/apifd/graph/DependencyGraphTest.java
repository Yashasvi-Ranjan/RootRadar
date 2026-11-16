package com.rootradar.apifd.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rootradar.apifd.monitoring.NodeType;
import java.util.List;
import org.junit.jupiter.api.Test;

class DependencyGraphTest {

    private static DependencyGraph demo() {
        return DependencyGraph.of(
                List.of(new GraphNode("gateway-service", NodeType.HTTP_SERVICE),
                        new GraphNode("order-service", NodeType.HTTP_SERVICE),
                        new GraphNode("payment-service", NodeType.HTTP_SERVICE),
                        new GraphNode("database", NodeType.DEPENDENCY_ONLY)),
                List.of(new GraphEdge("gateway-service", "order-service"),
                        new GraphEdge("order-service", "payment-service"),
                        new GraphEdge("payment-service", "database")));
    }

    @Test
    void traversalFollowsCallDirection() {
        DependencyGraph g = demo();
        assertEquals(List.of("order-service", "gateway-service"), List.copyOf(g.upstream("payment-service")));
        assertEquals(List.of("database"), List.copyOf(g.downstream("payment-service")));
        assertEquals(2, g.distance("order-service", "database"));
        assertEquals(List.of("gateway-service"), g.roots());
        assertEquals(List.of("database"), g.leaves());
        assertEquals(List.of("gateway-service", "order-service", "payment-service", "database"), g.topologicalOrder());
    }

    @Test
    void rejectsInvalidEdges() {
        DependencyGraph g = demo();
        assertEquals(InvalidGraphException.Reason.SELF_LOOP,
                assertThrows(InvalidGraphException.class, () -> g.withEdge("order-service", "order-service")).getReason());
        assertEquals(InvalidGraphException.Reason.UNKNOWN_NODE,
                assertThrows(InvalidGraphException.class, () -> g.withEdge("order-service", "nope")).getReason());
        assertEquals(InvalidGraphException.Reason.DUPLICATE_EDGE,
                assertThrows(InvalidGraphException.class, () -> g.withEdge("gateway-service", "order-service")).getReason());
        assertEquals(InvalidGraphException.Reason.CYCLE,
                assertThrows(InvalidGraphException.class, () -> g.withEdge("database", "gateway-service")).getReason());
        assertTrue(g.hasPath("gateway-service", "database"));
    }

    @Test
    void impactedAndDirectTraversalAndImmutability() {
        DependencyGraph g = demo();
        assertEquals(List.of("order-service"), List.copyOf(g.directUpstream("payment-service")));
        assertEquals(List.of("order-service", "payment-service", "database"), List.copyOf(g.downstream("gateway-service")));
        assertEquals(3, g.distance("gateway-service", "database"));
        assertEquals(-1, g.distance("database", "gateway-service"));
        assertTrue(g.upstream("gateway-service").isEmpty());
        assertTrue(g.upstream("unknown").isEmpty());
        DependencyGraph g2 = g.withEdge("gateway-service", "payment-service");
        assertEquals(3, g.edges().size(), "withEdge returns a new graph, original untouched");
        assertEquals(4, g2.edges().size());
        assertEquals(2, g2.distance("gateway-service", "database"));
    }

    @Test
    void diamondIsAllowedButLongCycleIsNot() {
        DependencyGraph d = DependencyGraph.of(
                List.of(new GraphNode("a", NodeType.HTTP_SERVICE), new GraphNode("b", NodeType.HTTP_SERVICE),
                        new GraphNode("c", NodeType.HTTP_SERVICE), new GraphNode("d", NodeType.HTTP_SERVICE)),
                List.of(new GraphEdge("a", "b"), new GraphEdge("a", "c"), new GraphEdge("b", "d"), new GraphEdge("c", "d")));
        assertEquals(3, d.upstream("d").size());
        assertEquals(InvalidGraphException.Reason.CYCLE,
                assertThrows(InvalidGraphException.class, () -> d.withEdge("d", "a")).getReason());
    }
}
