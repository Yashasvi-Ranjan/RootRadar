package com.rootradar.apifd.realtime;

/**
 * Fire-and-forget fan-out to connected SSE clients. Implemented by Agent 5 (bean {@code SseEventPublisher}).
 * Contract: never blocks the caller, never throws (a failing/slow client is dropped), safe to call from any thread,
 * a no-op when nobody is connected. Callers (monitoring, telemetry, incident) only construct {@link LiveEvent}s.
 */
public interface EventPublisher {
    void publish(LiveEvent event);
}
