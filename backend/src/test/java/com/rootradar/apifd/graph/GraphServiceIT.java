package com.rootradar.apifd.graph;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.testsupport.mon.MonitoringIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Edge persistence + validation + traversal + visualisation, service layer and REST. */
@AutoConfigureMockMvc
class GraphServiceIT extends MonitoringIT {

    @Autowired DependencyGraphService graph;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private MonitoredService gw, ord, pay, db;

    private void chain() {
        gw = registerHttp("gw", "http://localhost:1", 500);
        ord = registerHttp("ord", "http://localhost:1", 500);
        pay = registerHttp("pay", "http://localhost:1", 500);
        db = registerDependencyOnly("db");
        graph.addEdge(new AddEdgeRequest(gw.id(), ord.id(), null));
        graph.addEdge(new AddEdgeRequest(ord.id(), pay.id(), null));
        graph.addEdge(new AddEdgeRequest(pay.id(), db.id(), "database"));
    }

    private List<String> names(TraversalView v) {
        return v.services().stream().map(com.rootradar.apifd.common.ServiceRef::name).toList();
    }

    @Test
    void edgesPersistAndDefaultMeasurementNameIsTargetName() {
        chain();
        List<GraphEdgeView> mine = graph.listEdges().stream()
                .filter(e -> e.fromService().startsWith("mon-t-")).toList();
        assertEquals(3, mine.size());
        GraphEdgeView first = mine.get(0);
        assertEquals(gw.name(), first.fromService());
        assertEquals(ord.name(), first.toService());
        assertEquals(ord.name(), first.measurementName());
        assertEquals("database", mine.get(2).measurementName());
        assertEquals(3, jdbc.queryForObject("select count(*) from service_dependencies where from_service_id in (?,?,?)",
                Integer.class, gw.id(), ord.id(), pay.id()));
    }

    @Test
    void validationSelfLoopUnknownDuplicateCycle() {
        chain();
        ApiException.BadRequest self = assertThrows(ApiException.BadRequest.class,
                () -> graph.addEdge(new AddEdgeRequest(gw.id(), gw.id(), null)));
        assertEquals("INVALID_GRAPH", self.getCode());
        assertThrows(ApiException.NotFound.class, () -> graph.addEdge(new AddEdgeRequest(gw.id(), -77L, null)));
        assertThrows(ApiException.NotFound.class, () -> graph.addEdge(new AddEdgeRequest(-77L, gw.id(), null)));
        ApiException.Conflict dup = assertThrows(ApiException.Conflict.class,
                () -> graph.addEdge(new AddEdgeRequest(gw.id(), ord.id(), "other")));
        assertEquals("INVALID_GRAPH", dup.getCode());
        ApiException.Conflict cycle = assertThrows(ApiException.Conflict.class,
                () -> graph.addEdge(new AddEdgeRequest(db.id(), gw.id(), null)));
        assertEquals("INVALID_GRAPH", cycle.getCode());
        assertTrue(cycle.getMessage().contains("cycle"));
        // measurementName must be unique per caller
        ApiException.Conflict m = assertThrows(ApiException.Conflict.class,
                () -> graph.addEdge(new AddEdgeRequest(gw.id(), pay.id(), ord.name())));
        assertEquals("CONFLICT", m.getCode());
        assertEquals(3, graph.listEdges().stream().filter(e -> e.fromService().startsWith("mon-t-")).count(),
                "failed additions must not persist anything");
    }

    @Test
    void traversalFollowsCallDirection() {
        chain();
        assertEquals(List.of(ord.name(), gw.name()), names(graph.upstream(pay.id(), true)));
        assertEquals(List.of(ord.name()), names(graph.upstream(pay.id(), false)));
        assertEquals(List.of(db.name()), names(graph.downstream(pay.id(), true)));
        assertEquals(List.of(pay.name(), db.name()), names(graph.downstream(ord.id(), true)));
        assertEquals(List.of(pay.name()), names(graph.downstream(ord.id(), false)));
        TraversalView impact = graph.impact(db.id());
        assertEquals("UPSTREAM", impact.direction());
        assertTrue(impact.transitive());
        assertEquals(List.of(pay.name(), ord.name(), gw.name()), names(impact));
        assertEquals(db.id(), impact.origin().id());
        assertThrows(ApiException.NotFound.class, () -> graph.impact(-1));
        assertEquals(0, graph.upstream(gw.id(), true).services().size());
    }

    @Test
    void currentGraphMatchesPersistedEdgesAndViewHasLayersHealthAndMetrics() {
        chain();
        DependencyGraph g = graph.currentGraph();
        assertTrue(g.hasPath(gw.name(), db.name()));
        assertEquals(List.of(ord.name(), pay.name(), db.name()), List.copyOf(g.downstream(gw.name())));

        GraphView v = graph.view();
        GraphNodeView gwNode = v.nodes().stream().filter(n -> n.serviceId() == gw.id()).findFirst().orElseThrow();
        GraphNodeView dbNode = v.nodes().stream().filter(n -> n.serviceId() == db.id()).findFirst().orElseThrow();
        GraphNodeView payNode = v.nodes().stream().filter(n -> n.serviceId() == pay.id()).findFirst().orElseThrow();
        assertEquals(0, gwNode.layer());
        assertEquals(2, payNode.layer());
        assertEquals(3, dbNode.layer());
        assertEquals(com.rootradar.apifd.monitoring.HealthStatus.UNKNOWN, dbNode.healthStatus());
        assertEquals(com.rootradar.apifd.monitoring.HealthStatus.UNKNOWN, gwNode.healthStatus()); // never checked
        assertEquals(null, gwNode.recentMetrics());
        assertTrue(v.edges().stream().anyMatch(e -> e.toService().equals(db.name()) && e.measurementName().equals("database")));

        jdbc.update("insert into health_check_results (service_id, checked_at, status, outcome) values (?, now(), 'DOWN', 'TIMEOUT')", gw.id());
        GraphView v2 = graph.view();
        assertEquals(com.rootradar.apifd.monitoring.HealthStatus.DOWN,
                v2.nodes().stream().filter(n -> n.serviceId() == gw.id()).findFirst().orElseThrow().healthStatus());
    }

    @Test
    void removeEdgeAndDeletingServiceCascades() {
        chain();
        GraphEdgeView e = graph.listEdges().stream().filter(x -> x.toServiceId() == pay.id()).findFirst().orElseThrow();
        graph.removeEdge(e.id());
        assertThrows(ApiException.NotFound.class, () -> graph.removeEdge(e.id()));
        assertTrue(graph.upstream(pay.id(), true).services().isEmpty());
        // deleting a node drops its edges (ON DELETE CASCADE)
        registry.delete(db.id());
        assertEquals(1, graph.listEdges().stream().filter(x -> x.fromService().startsWith("mon-t-")).count());
    }

    @Test
    void restContract() throws Exception {
        gw = registerHttp("rgw", "http://localhost:1", 500);
        ord = registerHttp("rord", "http://localhost:1", 500);
        String json = "{\"fromServiceId\":" + gw.id() + ",\"toServiceId\":" + ord.id() + "}";
        String created = mvc.perform(post("/api/graph/edges").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromService").value(gw.name()))
                .andExpect(jsonPath("$.measurementName").value(ord.name()))
                .andReturn().getResponse().getContentAsString();
        long edgeId = mapper.readTree(created).get("id").asLong();

        mvc.perform(post("/api/graph/edges").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_GRAPH"));
        mvc.perform(post("/api/graph/edges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromServiceId\":" + ord.id() + ",\"toServiceId\":" + gw.id() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_GRAPH"));
        mvc.perform(post("/api/graph/edges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromServiceId\":" + gw.id() + ",\"toServiceId\":" + gw.id() + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GRAPH"));
        mvc.perform(post("/api/graph/edges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromServiceId\":" + gw.id() + ",\"toServiceId\":999999}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/graph/edges").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(get("/api/graph")).andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.edges").isArray());
        mvc.perform(get("/api/graph/edges")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/graph/services/" + ord.id() + "/upstream"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.direction").value("UPSTREAM"))
                .andExpect(jsonPath("$.origin.name").value(ord.name()))
                .andExpect(jsonPath("$.services", hasSize(1)))
                .andExpect(jsonPath("$.services[0].name").value(gw.name()));
        mvc.perform(get("/api/graph/services/" + gw.id() + "/downstream?transitive=false"))
                .andExpect(jsonPath("$.direction").value("DOWNSTREAM")).andExpect(jsonPath("$.transitive").value(false))
                .andExpect(jsonPath("$.services[0].name").value(ord.name()));
        mvc.perform(get("/api/graph/services/" + ord.id() + "/impact"))
                .andExpect(jsonPath("$.services[0].name").value(gw.name()));
        mvc.perform(get("/api/graph/services/999999/impact")).andExpect(status().isNotFound());

        mvc.perform(delete("/api/graph/edges/" + edgeId)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/graph/edges/" + edgeId)).andExpect(status().isNotFound());
    }
}
