package com.rootradar.apifd.monitoring;

import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import com.rootradar.apifd.monitoring.persistence.ServiceEntity;
import com.rootradar.apifd.monitoring.persistence.ServiceRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed {@link ServiceRegistry} with SSRF validation of every URL that is stored. */
@Service
public class JpaServiceRegistry implements ServiceRegistry {

    private static final String REFERENCED_SQL = """
            select exists(select 1 from incidents where root_cause_service_id = ?)
                or exists(select 1 from incident_services where service_id = ?)
                or exists(select 1 from anomaly_events where subject_service_id = ? or observed_by_service_id = ?)
                or exists(select 1 from incident_timeline_events where service_id = ?)
                or exists(select 1 from incident_diagnoses where probable_cause_service_id = ?)
                or exists(select 1 from incident_diagnosis_findings where service_id = ?)
            """;

    private final ServiceRepository repo;
    private final OutboundUrlPolicy policy;
    private final ApifdProperties props;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public JpaServiceRegistry(ServiceRepository repo, OutboundUrlPolicy policy, ApifdProperties props, Clock clock,
                              JdbcTemplate jdbc) {
        this.repo = repo;
        this.policy = policy;
        this.props = props;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MonitoredService> findAll() {
        return repo.findAll(Sort.by("id")).stream().map(JpaServiceRegistry::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MonitoredService> findById(long id) {
        return repo.findById(id).map(JpaServiceRegistry::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MonitoredService> findByName(String name) {
        return repo.findByName(name).map(JpaServiceRegistry::toDto);
    }

    @Override
    @Transactional
    public MonitoredService register(RegisterServiceRequest r) {
        if (repo.existsByName(r.name())) {
            throw new ApiException.Conflict("CONFLICT", "A service named '" + r.name() + "' already exists");
        }
        if (r.nodeType() == NodeType.HTTP_SERVICE) {
            if (isBlank(r.baseUrl()) || isBlank(r.healthEndpoint())) {
                throw new ApiException.BadRequest("VALIDATION_FAILED",
                        "baseUrl and healthEndpoint are required for HTTP_SERVICE nodes");
            }
            policy.validateBaseUrl(r.baseUrl());
            policy.validatePath(r.healthEndpoint());
            if (r.telemetryEndpoint() != null) policy.validatePath(r.telemetryEndpoint());
        } else if (r.baseUrl() != null || r.healthEndpoint() != null || r.telemetryEndpoint() != null) {
            throw new ApiException.BadRequest("VALIDATION_FAILED",
                    "baseUrl, healthEndpoint and telemetryEndpoint must be null for DEPENDENCY_ONLY nodes");
        }
        var now = clock.instant();
        ServiceEntity e = new ServiceEntity(r.name());
        e.setDisplayName(isBlank(r.displayName()) ? r.name() : r.displayName());
        e.setNodeType(r.nodeType());
        e.setBaseUrl(r.baseUrl());
        e.setHealthEndpoint(r.healthEndpoint());
        e.setTelemetryEndpoint(r.telemetryEndpoint());
        e.setCheckIntervalSeconds(r.checkIntervalSeconds() != null ? r.checkIntervalSeconds()
                : props.monitoring().defaultIntervalSeconds());
        e.setTimeoutMs(r.timeoutMs() != null ? r.timeoutMs() : props.monitoring().defaultTimeoutMs());
        e.setEnabled(r.enabled() == null || r.enabled());
        e.setDemo(false);
        e.setDescription(r.description());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return toDto(repo.saveAndFlush(e));
    }

    @Override
    @Transactional
    public MonitoredService update(long id, UpdateServiceRequest r) {
        ServiceEntity e = repo.findById(id).orElseThrow(() -> new ApiException.NotFound("Service " + id + " not found"));
        if (e.getNodeType() == NodeType.DEPENDENCY_ONLY
                && (r.baseUrl() != null || r.healthEndpoint() != null || r.telemetryEndpoint() != null)) {
            throw new ApiException.BadRequest("VALIDATION_FAILED", "URL fields cannot be set on a DEPENDENCY_ONLY node");
        }
        if (r.baseUrl() != null) {
            policy.validateBaseUrl(r.baseUrl());
            e.setBaseUrl(r.baseUrl());
        }
        if (r.healthEndpoint() != null) {
            policy.validatePath(r.healthEndpoint());
            e.setHealthEndpoint(r.healthEndpoint());
        }
        if (r.telemetryEndpoint() != null) {
            policy.validatePath(r.telemetryEndpoint());
            e.setTelemetryEndpoint(r.telemetryEndpoint());
        }
        if (r.displayName() != null) e.setDisplayName(r.displayName().isBlank() ? e.getName() : r.displayName());
        if (r.checkIntervalSeconds() != null) e.setCheckIntervalSeconds(r.checkIntervalSeconds());
        if (r.timeoutMs() != null) e.setTimeoutMs(r.timeoutMs());
        if (r.enabled() != null) e.setEnabled(r.enabled());
        if (r.description() != null) e.setDescription(r.description());
        e.setUpdatedAt(clock.instant());
        return toDto(repo.saveAndFlush(e));
    }

    @Override
    @Transactional
    public void delete(long id) {
        if (!repo.existsById(id)) throw new ApiException.NotFound("Service " + id + " not found");
        Boolean referenced = jdbc.queryForObject(REFERENCED_SQL, Boolean.class, id, id, id, id, id, id, id);
        if (Boolean.TRUE.equals(referenced)) {
            throw new ApiException.Conflict("CONFLICT",
                    "Service is referenced by incident history; disable it instead of deleting it");
        }
        repo.deleteById(id);
        repo.flush();
    }

    static MonitoredService toDto(ServiceEntity e) {
        return new MonitoredService(e.getId(), e.getName(), e.getDisplayName(), e.getNodeType(), e.getBaseUrl(),
                e.getHealthEndpoint(), e.getTelemetryEndpoint(), e.getCheckIntervalSeconds(), e.getTimeoutMs(),
                e.isEnabled(), e.isDemo(), e.getDescription(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
