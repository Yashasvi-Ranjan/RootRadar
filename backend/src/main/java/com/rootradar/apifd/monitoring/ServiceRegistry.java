package com.rootradar.apifd.monitoring;

import java.util.List;
import java.util.Optional;

/**
 * Source of truth for registered nodes. Implemented by Agent 2 (bean name: {@code JpaServiceRegistry}).
 * Other packages (graph, anomaly pipeline, incident) depend ONLY on this interface, never on the repositories.
 *
 * <p>Errors (thrown as {@link com.rootradar.apifd.common.ApiException} subclasses):
 * duplicate name -> Conflict("CONFLICT"); URL fails SSRF policy -> BadRequest("URL_NOT_ALLOWED");
 * missing id -> NotFound; HTTP_SERVICE without baseUrl/healthEndpoint -> BadRequest("VALIDATION_FAILED").
 */
public interface ServiceRegistry {

    /** All nodes (enabled or not), ordered by id. */
    List<MonitoredService> findAll();

    Optional<MonitoredService> findById(long id);

    Optional<MonitoredService> findByName(String name);

    /** Persist a new node. Returns the stored node with id/timestamps. */
    MonitoredService register(RegisterServiceRequest request);

    /** Partial update; re-validates URL policy if baseUrl changes. Throws NotFound if id unknown. */
    MonitoredService update(long id, UpdateServiceRequest request);

    /** Deletes the node; cascades to its edges, health results, telemetry and rules. Incident history that references
     *  it is protected: throws Conflict("CONFLICT") if any incident/anomaly references the node (disable it instead). */
    void delete(long id);
}
