package com.rootradar.apifd.realtime;

import org.springframework.stereotype.Component;

/** {@link EventPublisher} backed by the {@link SseHub}. */
@Component
public class SseEventPublisher implements EventPublisher {
    private final SseHub hub;

    public SseEventPublisher(SseHub hub) {
        this.hub = hub;
    }

    @Override
    public void publish(LiveEvent event) {
        hub.publish(event);
    }
}
