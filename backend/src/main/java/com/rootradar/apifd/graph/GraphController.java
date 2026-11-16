package com.rootradar.apifd.graph;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST: dependency graph visualisation, edge CRUD and traversal (API_CONTRACTS section 3). */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final DependencyGraphService graph;

    public GraphController(DependencyGraphService graph) {
        this.graph = graph;
    }

    @GetMapping
    public GraphView view() {
        return graph.view();
    }

    @GetMapping("/edges")
    public List<GraphEdgeView> edges() {
        return graph.listEdges();
    }

    @PostMapping("/edges")
    public ResponseEntity<GraphEdgeView> addEdge(@Valid @RequestBody AddEdgeRequest request) {
        GraphEdgeView created = graph.addEdge(request);
        return ResponseEntity.created(URI.create("/api/graph/edges/" + created.id())).body(created);
    }

    @DeleteMapping("/edges/{edgeId}")
    public ResponseEntity<Void> removeEdge(@PathVariable long edgeId) {
        graph.removeEdge(edgeId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/services/{id}/upstream")
    public TraversalView upstream(@PathVariable long id, @RequestParam(defaultValue = "true") boolean transitive) {
        return graph.upstream(id, transitive);
    }

    @GetMapping("/services/{id}/downstream")
    public TraversalView downstream(@PathVariable long id, @RequestParam(defaultValue = "true") boolean transitive) {
        return graph.downstream(id, transitive);
    }

    @GetMapping("/services/{id}/impact")
    public TraversalView impact(@PathVariable long id) {
        return graph.impact(id);
    }
}
