package com.rootradar.apifd.telemetry.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Row of {@code telemetry_poll_results}: outcome of one poll of a service's telemetry endpoint. */
@Entity
@Table(name = "telemetry_poll_results")
public class PollResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "service_id", nullable = false)
    private long serviceId;
    @Column(name = "polled_at", nullable = false)
    private Instant polledAt;
    @Column(nullable = false)
    private boolean success;
    @Column(name = "duration_ms")
    private Integer durationMs;
    @Column(name = "windows_received", nullable = false)
    private int windowsReceived;
    @Column(name = "windows_stored", nullable = false)
    private int windowsStored;
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    protected PollResultEntity() {}

    public PollResultEntity(long serviceId, Instant polledAt, boolean success, Integer durationMs,
                            int windowsReceived, int windowsStored, String errorMessage) {
        this.serviceId = serviceId;
        this.polledAt = polledAt;
        this.success = success;
        this.durationMs = durationMs;
        this.windowsReceived = windowsReceived;
        this.windowsStored = windowsStored;
        this.errorMessage = errorMessage == null || errorMessage.length() <= 500
                ? errorMessage : errorMessage.substring(0, 500);
    }

    public Long getId() { return id; }
    public long getServiceId() { return serviceId; }
    public Instant getPolledAt() { return polledAt; }
    public boolean isSuccess() { return success; }
    public Integer getDurationMs() { return durationMs; }
    public int getWindowsReceived() { return windowsReceived; }
    public int getWindowsStored() { return windowsStored; }
    public String getErrorMessage() { return errorMessage; }
}
