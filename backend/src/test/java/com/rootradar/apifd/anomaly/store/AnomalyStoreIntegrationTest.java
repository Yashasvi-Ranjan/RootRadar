package com.rootradar.apifd.anomaly.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.anomaly.AnomalyEventView;
import com.rootradar.apifd.anomaly.AnomalyType;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.incident.AbstractIncidentIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** Fingerprint upsert semantics and JSON round trip of anomaly_events. */
class AnomalyStoreIntegrationTest extends AbstractIncidentIntegrationTest {
    @Autowired AnomalyStore store;
    @Autowired ServiceLookup lookup;

    @Test
    void upsertRefreshesSameFingerprintAndTracksSeverityChangeAndClearing() {
        var snap = lookup.snapshot();
        AnomalyEvent warn = anomaly(PAYMENT, AnomalyType.LATENCY, Severity.WARNING, t(0), t(0).minusSeconds(20));
        var first = store.sync(List.of(warn), t(0), snap);
        assertThat(first).singleElement().satisfies(c -> assertThat(c.kind()).isEqualTo(AnomalyStore.Kind.CREATED));

        var second = store.sync(List.of(warn), t(1), snap);
        assertThat(second).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(AnomalyStore.Kind.REFRESHED);
            assertThat(c.entity().occurrenceCount).isEqualTo(2);
            assertThat(c.entity().lastSeenAt).isEqualTo(t(1));
        });

        AnomalyEvent crit = anomaly(PAYMENT, AnomalyType.LATENCY, Severity.CRITICAL, t(2), t(0).minusSeconds(20));
        var third = store.sync(List.of(crit), t(2), snap);
        assertThat(third.get(0).kind()).isEqualTo(AnomalyStore.Kind.SEVERITY_CHANGED);
        assertThat(third.get(0).entity().critical).isTrue();

        var cleared = store.sync(List.of(), t(3), snap);
        assertThat(cleared).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(AnomalyStore.Kind.CLEARED);
            assertThat(c.entity().clearedAt).isEqualTo(t(3));
        });
        assertThat(count("fingerprint LIKE '%|" + PAYMENT + "|%'")).isEqualTo(1);

        // a later episode of the same fingerprint is a new row
        var again = store.sync(List.of(warn), t(4), snap);
        assertThat(again.get(0).kind()).isEqualTo(AnomalyStore.Kind.CREATED);
        assertThat(count("fingerprint LIKE '%|" + PAYMENT + "|%'")).isEqualTo(2);
    }

    @Test
    void detailsAndMeasurementsRoundTripThroughJsonb() {
        var snap = lookup.snapshot();
        store.sync(List.of(anomaly(PAYMENT, AnomalyType.LATENCY, Severity.WARNING, t(0), t(0).minusSeconds(20))),
                t(0), snap);
        AnomalyEventView v = store.search(PAYMENT, null, null, "OPEN", PageRequest.of(0, 10), snap).getContent().get(0);
        assertThat(v.event().details()).containsEntry("mad", 4.2);
        assertThat(v.event().observedValue()).isEqualTo(2011.0);
        assertThat(v.event().baselineValue()).isEqualTo(46.0);
        assertThat(v.event().subjectService()).isEqualTo(PAYMENT);
        assertThat(v.event().firstBreachAt()).isEqualTo(t(0).minusSeconds(20));
        assertThat(v.incidentId()).isNull();
    }

    @Test
    void unknownServicesAreSkippedNotPersisted() {
        var snap = lookup.snapshot();
        AnomalyEvent ghost = anomaly("t5-ghost", AnomalyType.ERROR_RATE, Severity.WARNING, t(0), t(0));
        assertThat(store.sync(List.of(ghost), t(0), snap)).isEmpty();
    }

    @Test
    void healthCheckAnomalyKeepsItsScope() {
        var snap = lookup.snapshot();
        AnomalyEvent h = new AnomalyEvent(AnomalyEvent.healthFingerprint(PAYMENT), AnomalyType.HEALTH_CHECK_FAILURE,
                Severity.CRITICAL, PAYMENT, com.rootradar.apifd.telemetry.MetricScope.HEALTH_CHECK, null, null, null,
                null, com.rootradar.apifd.anomaly.DetectionMethod.CONSECUTIVE_FAILURES, "health-default", t(0), t(0),
                t(0), t(0), 3.0, null, 3.0, null, 3, 3, 0, "3 failed checks", java.util.Map.of());
        store.sync(List.of(h), t(0), snap);
        var v = store.search(PAYMENT, null, null, null, PageRequest.of(0, 5), snap).getContent().get(0);
        assertThat(v.event().scope()).isEqualTo(com.rootradar.apifd.telemetry.MetricScope.HEALTH_CHECK);
        assertThat(v.event().type()).isEqualTo(AnomalyType.HEALTH_CHECK_FAILURE);
        assertThat(SeriesState.values()).isNotEmpty();
    }

    private int count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM anomaly_events WHERE " + where, Integer.class);
    }
}
