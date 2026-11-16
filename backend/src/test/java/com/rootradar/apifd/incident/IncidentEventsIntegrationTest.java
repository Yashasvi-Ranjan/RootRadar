package com.rootradar.apifd.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.realtime.LiveEvent;
import com.rootradar.apifd.realtime.LiveEventType;
import com.rootradar.apifd.realtime.LiveEvents;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Events are published through EventPublisher only after the transaction commits. */
class IncidentEventsIntegrationTest extends AbstractIncidentIntegrationTest {
    @Autowired TransactionTemplate tx;

    private List<LiveEvent> published() {
        ArgumentCaptor<LiveEvent> c = ArgumentCaptor.forClass(LiveEvent.class);
        verify(publisher, Mockito.atLeast(0)).publish(c.capture());
        return c.getAllValues();
    }

    @Test
    void openingPublishesAnomalyIncidentAndDiagnosisEvents() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        List<LiveEvent> events = published();
        assertThat(events).extracting(LiveEvent::type).containsExactlyInAnyOrder(LiveEventType.ANOMALY_DETECTED,
                LiveEventType.INCIDENT_OPENED, LiveEventType.DIAGNOSIS_UPDATED);
        LiveEvent opened = events.stream().filter(e -> e.type() == LiveEventType.INCIDENT_OPENED).findFirst().orElseThrow();
        var payload = (LiveEvents.IncidentChanged) opened.data();
        assertThat(payload.change()).isEqualTo(IncidentChangeType.OPENED);
        // summary reflects the final state of the call (confirmed + diagnosed), not an intermediate one
        assertThat(payload.incident().status()).isEqualTo(IncidentStatus.ACTIVE);
        assertThat(payload.incident().rootCause().name()).isEqualTo(PAYMENT);
        var diagnosis = (LiveEvents.DiagnosisUpdated) events.stream()
                .filter(e -> e.type() == LiveEventType.DIAGNOSIS_UPDATED).findFirst().orElseThrow().data();
        assertThat(diagnosis.probableCauseService()).isEqualTo(PAYMENT);
        assertThat(diagnosis.topScore()).isEqualTo(85);
    }

    @Test
    void recoveryPublishesUpdatedClearedAndResolvedEvents() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        Mockito.clearInvocations(publisher);
        incidents.applyDetection(quiet(1, SeriesState.NORMAL, PAYMENT));
        assertThat(published()).extracting(LiveEvent::type).containsExactlyInAnyOrder(
                LiveEventType.ANOMALY_CLEARED, LiveEventType.INCIDENT_UPDATED);
        Mockito.clearInvocations(publisher);
        incidents.applyDetection(quiet(2, SeriesState.NORMAL, PAYMENT));
        assertThat(published()).isEmpty();
        incidents.applyDetection(quiet(3, SeriesState.NORMAL, PAYMENT));
        assertThat(published()).extracting(LiveEvent::type).containsExactly(LiveEventType.INCIDENT_RESOLVED);
    }

    @Test
    void nothingIsPublishedWhenTheTransactionRollsBack() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        verify(publisher, never()).publish(any());
        assertThat(incidentCount()).isZero();
    }
}
