package com.rootradar.apifd.monitoring.persistence;

import com.rootradar.apifd.monitoring.NodeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Row of {@code services}. */
@Entity
@Table(name = "services")
public class ServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 100)
    private String name;
    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;
    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 20)
    private NodeType nodeType;
    @Column(name = "base_url", length = 500)
    private String baseUrl;
    @Column(name = "health_endpoint", length = 300)
    private String healthEndpoint;
    @Column(name = "telemetry_endpoint", length = 300)
    private String telemetryEndpoint;
    @Column(name = "check_interval_seconds", nullable = false)
    private int checkIntervalSeconds;
    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;
    @Column(nullable = false)
    private boolean enabled;
    @Column(nullable = false)
    private boolean demo;
    @Column(columnDefinition = "text")
    private String description;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServiceEntity() {}

    public ServiceEntity(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public NodeType getNodeType() { return nodeType; }
    public void setNodeType(NodeType v) { this.nodeType = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getHealthEndpoint() { return healthEndpoint; }
    public void setHealthEndpoint(String v) { this.healthEndpoint = v; }
    public String getTelemetryEndpoint() { return telemetryEndpoint; }
    public void setTelemetryEndpoint(String v) { this.telemetryEndpoint = v; }
    public int getCheckIntervalSeconds() { return checkIntervalSeconds; }
    public void setCheckIntervalSeconds(int v) { this.checkIntervalSeconds = v; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int v) { this.timeoutMs = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isDemo() { return demo; }
    public void setDemo(boolean v) { this.demo = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
