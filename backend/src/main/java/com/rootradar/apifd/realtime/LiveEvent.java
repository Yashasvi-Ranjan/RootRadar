package com.rootradar.apifd.realtime;

import java.time.Instant;

/**
 * Envelope of every pushed event. Serialised as the SSE data line:
 * {@code {"type":"incident.opened","timestamp":"...","data":{...payload...}}} where type = type.wireName().
 * Payload classes: {@link LiveEvents}.
 */
public record LiveEvent(LiveEventType type, Instant timestamp, Object data) {}
