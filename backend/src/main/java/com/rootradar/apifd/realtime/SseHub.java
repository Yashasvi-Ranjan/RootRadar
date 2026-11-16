package com.rootradar.apifd.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory fan-out of {@link LiveEvent}s to SSE clients. Bounded in every dimension: at most
 * {@code apifd.realtime.max-emitters} clients, and a single dispatcher thread with a bounded queue (events are
 * dropped, with a warning, when the queue overflows - REST remains the source of truth). A client whose write fails
 * is removed. No replay.
 */
@Component
public class SseHub {
    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    static final int QUEUE_CAPACITY = 1000;
    static final long RETRY_MS = 3000;

    private record Subscription(SseEmitter emitter, Set<LiveEventType> types) {
        boolean accepts(LiveEventType t) {
            return t == LiveEventType.HEARTBEAT || types == null || types.contains(t);
        }
    }

    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApifdProperties.Realtime config;
    private final Map<SseEmitter, Subscription> clients = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
                Thread t = new Thread(r, "sse-dispatcher");
                t.setDaemon(true);
                return t;
            });

    public SseHub(ObjectMapper mapper, Clock clock, ApifdProperties props) {
        this.mapper = mapper;
        this.clock = clock;
        this.config = props.realtime();
    }

    /**
     * Registers a client. Sends {@code retry: 3000} and an immediate heartbeat.
     * @param types wire-name filter, null/empty = all events (heartbeats always pass)
     * @throws ApiException 503 when max-emitters is reached
     */
    public SseEmitter register(Set<LiveEventType> types) {
        if (clients.size() >= config.maxEmitters()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TOO_MANY_STREAMS",
                    "Too many open event streams (max " + config.maxEmitters() + ")");
        }
        SseEmitter emitter = new SseEmitter(config.sseTimeoutMs());
        Subscription sub = new Subscription(emitter, types == null || types.isEmpty() ? null : Set.copyOf(types));
        Runnable remove = () -> clients.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(t -> remove.run());
        try {
            emitter.send(SseEmitter.event().reconnectTime(RETRY_MS));
            send(sub, heartbeat());
        } catch (IOException | RuntimeException ex) {
            log.debug("Initial SSE write failed: {}", ex.toString());
            return emitter;
        }
        clients.put(emitter, sub);
        return emitter;
    }

    /** Enqueues an event for all matching clients; never blocks, never throws. */
    public void publish(LiveEvent event) {
        if (event == null || clients.isEmpty()) return;
        try {
            dispatcher.execute(() -> dispatch(event));
        } catch (RejectedExecutionException ex) {
            log.warn("SSE queue full; dropping {} event", event.type());
        }
    }

    private void dispatch(LiveEvent event) {
        for (Subscription sub : clients.values()) {
            if (!sub.accepts(event.type())) continue;
            try {
                send(sub, event);
            } catch (IOException | RuntimeException ex) {
                clients.remove(sub.emitter());
                try {
                    sub.emitter().complete();
                } catch (RuntimeException ignored) {
                    // already closed
                }
            }
        }
    }

    private void send(Subscription sub, LiveEvent event) throws IOException {
        sub.emitter().send(SseEmitter.event().id(Long.toString(nextId.getAndIncrement()))
                .name(event.type().wireName()).data(toJson(event)));
    }

    private String toJson(LiveEvent event) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", event.type().wireName());
        envelope.put("timestamp", event.timestamp());
        envelope.put("data", event.data());
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IOException("Cannot serialise " + event.type(), ex);
        }
    }

    private LiveEvent heartbeat() {
        var now = clock.instant();
        return new LiveEvent(LiveEventType.HEARTBEAT, now, new LiveEvents.Heartbeat(now));
    }

    @Scheduled(initialDelayString = "#{${apifd.realtime.heartbeat-seconds:15} * 1000}",
            fixedRateString = "#{${apifd.realtime.heartbeat-seconds:15} * 1000}")
    void heartbeatTick() {
        publish(heartbeat());
    }

    public int clientCount() {
        return clients.size();
    }

    @PreDestroy
    void shutdown() {
        dispatcher.shutdownNow();
        clients.keySet().forEach(e -> {
            try {
                e.complete();
            } catch (RuntimeException ignored) {
                // best effort
            }
        });
        clients.clear();
    }
}
