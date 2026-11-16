package com.rootradar.apifd.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.config.ApifdProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Pure unit tests of the hub bounds and the filter parsing. */
class SseHubTest {
    private static SseHub hub(int maxEmitters) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ApifdProperties props = new ApifdProperties(null, false, null, null, null, null,
                new ApifdProperties.Realtime(0, 15, maxEmitters), null);
        return new SseHub(mapper, Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC), props);
    }

    @Test
    void rejectsClientsBeyondMaxEmittersWith503() {
        SseHub hub = hub(2);
        hub.register(null);
        hub.register(Set.of(LiveEventType.INCIDENT_OPENED));
        assertThat(hub.clientCount()).isEqualTo(2);
        assertThatThrownBy(() -> hub.register(null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        hub.shutdown();
        assertThat(hub.clientCount()).isZero();
    }

    @Test
    void publishWithoutClientsIsANoOpAndNeverThrows() {
        SseHub hub = hub(1);
        hub.publish(new LiveEvent(LiveEventType.HEARTBEAT, Instant.EPOCH, new LiveEvents.Heartbeat(Instant.EPOCH)));
        hub.publish(null);
        hub.shutdown();
    }

    @Test
    void parseTypesAcceptsWireNamesAndRejectsUnknown() {
        assertThat(StreamController.parseTypes(null)).isNull();
        assertThat(StreamController.parseTypes(" ")).isNull();
        assertThat(StreamController.parseTypes("incident.opened, anomaly.cleared"))
                .containsExactlyInAnyOrder(LiveEventType.INCIDENT_OPENED, LiveEventType.ANOMALY_CLEARED);
        assertThatThrownBy(() -> StreamController.parseTypes("INCIDENT_OPENED")).isInstanceOf(ApiException.class);
    }
}
