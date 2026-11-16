package com.rootradar.apifd.realtime;

import com.rootradar.apifd.common.ApiException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** GET /api/stream: server-sent events (docs/API_CONTRACTS.md section 6). */
@RestController
public class StreamController {
    private final SseHub hub;

    public StreamController(SseHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String types) {
        return hub.register(parseTypes(types));
    }

    static Set<LiveEventType> parseTypes(String types) {
        if (types == null || types.isBlank()) return null;
        Set<LiveEventType> out = EnumSet.noneOf(LiveEventType.class);
        for (String raw : types.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            LiveEventType t = Arrays.stream(LiveEventType.values()).filter(v -> v.wireName().equals(name)).findFirst()
                    .orElseThrow(() -> new ApiException.BadRequest("BAD_REQUEST", "Unknown event type '" + name + "'"));
            out.add(t);
        }
        return out;
    }
}
