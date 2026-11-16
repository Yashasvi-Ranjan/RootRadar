package com.rootradar.apifd.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootradar.apifd.anomaly.AnomalyEventView;
import com.rootradar.apifd.anomaly.AnomalyType;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.common.ServiceRef;
import com.rootradar.apifd.common.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lifecycle, dedup, hysteresis, severity and timeline behaviour of IncidentServiceImpl against apifd_test. */
class IncidentLifecycleIntegrationTest extends AbstractIncidentIntegrationTest {
    @Test
    void anomalyOpensDetectedIncidentThenConfirmsAfterSecondEvaluation() {
        List<IncidentChange> first = incidents.applyDetection(firing(0, Severity.WARNING, PAYMENT));
        assertThat(first).extracting(IncidentChange::type).contains(IncidentChangeType.OPENED);
        IncidentSummary opened = only();
        assertThat(opened.status()).isEqualTo(IncidentStatus.DETECTED);
        assertThat(opened.severity()).isEqualTo(Severity.WARNING);
        assertThat(opened.detectedAt()).isEqualTo(t(0));
        assertThat(opened.activatedAt()).isNull();

        incidents.applyDetection(firing(1, Severity.WARNING, PAYMENT));
        IncidentSummary active = only();
        assertThat(active.id()).isEqualTo(opened.id());
        assertThat(active.status()).isEqualTo(IncidentStatus.ACTIVE);
        assertThat(active.activatedAt()).isEqualTo(t(1));
        assertThat(timelineTypes(active.id())).containsSubsequence(TimelineEventType.INCIDENT_DETECTED,
                TimelineEventType.ANOMALY_DETECTED, TimelineEventType.STATUS_CHANGED);
        assertThat(incidentCount()).isEqualTo(1);
    }

    @Test
    void criticalAnomalyConfirmsImmediately() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        IncidentSummary s = only();
        assertThat(s.status()).isEqualTo(IncidentStatus.ACTIVE);
        assertThat(s.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void sameOngoingFailureNeverCreatesDuplicateIncidentOrAnomalyRows() {
        for (int i = 0; i < 6; i++) incidents.applyDetection(firing(i, Severity.WARNING, PAYMENT));
        assertThat(incidentCount()).isEqualTo(1);
        List<AnomalyEventView> anomalies = incidents.anomalies(only().id());
        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).occurrenceCount()).isEqualTo(6);
        assertThat(anomalies.get(0).status()).isEqualTo("OPEN");
        assertThat(anomalies.get(0).incidentId()).isEqualTo(only().id());
    }

    @Test
    void graphConnectedAnomaliesJoinTheSameIncidentAndUnrelatedOnesOpenAnother() {
        incidents.applyDetection(firing(0, Severity.WARNING, PAYMENT));
        // gateway is upstream of payment (path gateway -> order -> payment): same failure
        incidents.applyDetection(firing(1, Severity.WARNING, PAYMENT, GATEWAY));
        assertThat(incidentCount()).isEqualTo(1);
        IncidentSummary s = only();
        assertThat(s.impactedServices()).extracting(ServiceRef::name).contains(PAYMENT, GATEWAY, ORDER);
        assertThat(timelineTypes(s.id())).filteredOn(e -> e == TimelineEventType.ANOMALY_DETECTED).hasSize(2);

        // no path between t5-isolated and the chain: independent incident
        incidents.applyDetection(firing(2, Severity.WARNING, PAYMENT, GATEWAY, ISOLATED));
        assertThat(incidentCount()).isEqualTo(2);
        assertThat(incidents.findActive()).hasSize(2);
    }

    @Test
    void anomalyOnUpstreamServiceInSameEvaluationOpensSingleIncidentAndEarliestBreachOpens() {
        var gw = anomaly(GATEWAY, AnomalyType.ERROR_RATE, Severity.WARNING, t(0), t(0).minusSeconds(10));
        var pay = anomaly(PAYMENT, AnomalyType.LATENCY, Severity.WARNING, t(0), t(0).minusSeconds(40));
        incidents.applyDetection(detection(t(0), List.of(gw, pay), assessment(GATEWAY, SeriesState.ANOMALOUS),
                assessment(PAYMENT, SeriesState.ANOMALOUS)));
        assertThat(incidentCount()).isEqualTo(1);
        // payment breached first so it opened the incident
        assertThat(jdbc.queryForObject("SELECT dedup_key FROM incidents WHERE dedup_key LIKE '%|t5-%'", String.class))
                .contains(PAYMENT);
    }

    @Test
    void recoveryRequiresConsecutiveNormalEvaluationsThenResolves() {
        incidents.applyDetection(firing(0, Severity.WARNING, PAYMENT));
        incidents.applyDetection(firing(1, Severity.WARNING, PAYMENT));
        long id = only().id();

        incidents.applyDetection(quiet(2, SeriesState.NORMAL, PAYMENT));
        assertThat(only().status()).isEqualTo(IncidentStatus.RECOVERING);
        assertThat(only().recoveringAt()).isEqualTo(t(2));
        incidents.applyDetection(quiet(3, SeriesState.NORMAL, PAYMENT));
        assertThat(only().status()).as("2 normal evaluations are not enough").isEqualTo(IncidentStatus.RECOVERING);
        incidents.applyDetection(quiet(4, SeriesState.NORMAL, PAYMENT));

        assertThat(incidents.findActive()).isEmpty();
        IncidentSummary resolved = incidents.findSummary(id).orElseThrow();
        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isEqualTo(t(4));
        assertThat(resolved.resolutionReason()).isEqualTo("RECOVERED");
        assertThat(timelineTypes(id)).containsSubsequence(TimelineEventType.INCIDENT_DETECTED,
                TimelineEventType.STATUS_CHANGED, TimelineEventType.ANOMALY_CLEARED,
                TimelineEventType.RECOVERY_STARTED, TimelineEventType.RESOLVED);
        assertThat(incidents.anomalies(id)).allMatch(a -> a.status().equals("CLEARED") && a.clearedAt() != null);
        // history and evidence stay accessible after resolution
        assertThat(incidents.getDetail(id).latestDiagnosis()).isNotNull();
    }

    @Test
    void returningDegradationDuringRecoveryAbortsRecoveryWithoutNewIncident() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        long id = only().id();
        incidents.applyDetection(quiet(1, SeriesState.NORMAL, PAYMENT));
        incidents.applyDetection(quiet(2, SeriesState.NORMAL, PAYMENT));
        assertThat(only().status()).isEqualTo(IncidentStatus.RECOVERING);

        incidents.applyDetection(firing(3, Severity.WARNING, PAYMENT));
        IncidentSummary s = only();
        assertThat(s.id()).isEqualTo(id);
        assertThat(s.status()).isEqualTo(IncidentStatus.ACTIVE);
        assertThat(s.recoveringAt()).isNull();
        assertThat(incidentCount()).isEqualTo(1);
        assertThat(timelineTypes(id)).contains(TimelineEventType.RECOVERY_ABORTED);

        // the normal streak restarts: two normal evaluations after the relapse are still not enough
        incidents.applyDetection(quiet(4, SeriesState.NORMAL, PAYMENT));
        incidents.applyDetection(quiet(5, SeriesState.NORMAL, PAYMENT));
        assertThat(only().status()).isEqualTo(IncidentStatus.RECOVERING);
        incidents.applyDetection(quiet(6, SeriesState.NORMAL, PAYMENT));
        assertThat(incidents.findActive()).isEmpty();
        assertThat(incidents.findSummary(id).orElseThrow().status()).isEqualTo(IncidentStatus.RESOLVED);
    }

    @Test
    void missingDataDuringRecoveryResetsTheNormalStreak() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        incidents.applyDetection(quiet(1, SeriesState.NORMAL, PAYMENT));   // streak 1
        incidents.applyDetection(quiet(2, SeriesState.NORMAL, PAYMENT));   // 2
        incidents.applyDetection(quiet(3, SeriesState.NO_DATA, PAYMENT));  // reset
        incidents.applyDetection(quiet(4, SeriesState.NORMAL, PAYMENT));   // 1
        incidents.applyDetection(quiet(5, SeriesState.NORMAL, PAYMENT));   // 2
        assertThat(only().status()).isEqualTo(IncidentStatus.RECOVERING);
        incidents.applyDetection(quiet(6, SeriesState.NORMAL, PAYMENT));   // 3
        assertThat(incidents.findActive()).isEmpty();
    }

    @Test
    void insufficientBaselineIsNeverTreatedAsNormal() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        for (int i = 1; i < 6; i++) incidents.applyDetection(quiet(i, SeriesState.INSUFFICIENT_BASELINE, PAYMENT));
        assertThat(only().status()).isEqualTo(IncidentStatus.ACTIVE);
    }

    @Test
    void incidentThatClearsBeforeConfirmationResolvesDirectly() {
        incidents.applyDetection(firing(0, Severity.WARNING, PAYMENT));
        long id = only().id();
        incidents.applyDetection(quiet(1, SeriesState.NORMAL, PAYMENT));
        IncidentSummary s = incidents.findSummary(id).orElseThrow();
        assertThat(s.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(s.resolutionReason()).isEqualTo("RECOVERED");
        assertThat(s.activatedAt()).isNull();
    }

    @Test
    void recurrenceWithinReopenWindowReopensSameIncidentAndLaterOneOpensNewIncident() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        long id = only().id();
        incidents.applyDetection(quiet(1, SeriesState.NORMAL, PAYMENT));
        incidents.applyDetection(quiet(2, SeriesState.NORMAL, PAYMENT));
        incidents.applyDetection(quiet(3, SeriesState.NORMAL, PAYMENT));
        assertThat(incidents.findActive()).isEmpty();

        // 60 s after resolution (window = 300 s): same incident returns, no flapping duplicate
        List<IncidentChange> changes = incidents.applyDetection(firing(9, Severity.WARNING, PAYMENT));
        assertThat(changes).extracting(IncidentChange::type).contains(IncidentChangeType.REOPENED);
        IncidentSummary reopened = only();
        assertThat(reopened.id()).isEqualTo(id);
        assertThat(reopened.status()).isEqualTo(IncidentStatus.ACTIVE);
        assertThat(reopened.reopenCount()).isEqualTo(1);
        assertThat(reopened.resolvedAt()).isNull();
        assertThat(incidentCount()).isEqualTo(1);
        assertThat(timelineTypes(id)).contains(TimelineEventType.REOPENED);

        // resolve again, then recur after the window
        incidents.applyDetection(quiet(10, SeriesState.NORMAL, PAYMENT));
        incidents.applyDetection(quiet(11, SeriesState.NORMAL, PAYMENT));
        incidents.applyDetection(quiet(12, SeriesState.NORMAL, PAYMENT));
        assertThat(incidents.findActive()).isEmpty();
        incidents.applyDetection(firing(12 + 31, Severity.WARNING, PAYMENT));
        assertThat(incidentCount()).isEqualTo(2);
        assertThat(only().id()).isNotEqualTo(id);
    }

    @Test
    void severityEscalatesButNeverDowngradesWhileOpen() {
        incidents.applyDetection(firing(0, Severity.WARNING, PAYMENT));
        incidents.applyDetection(firing(1, Severity.CRITICAL, PAYMENT));
        IncidentSummary s = only();
        assertThat(s.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(timelineTypes(s.id())).contains(TimelineEventType.SEVERITY_CHANGED);
        incidents.applyDetection(firing(2, Severity.WARNING, PAYMENT));
        assertThat(only().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(incidentCount()).isEqualTo(1);
    }

    @Test
    void openIncidentWithoutValidSamplesIsResolvedAfterNoDataTimeout() {
        incidents.applyDetection(firing(0, Severity.CRITICAL, PAYMENT));
        long id = only().id();
        incidents.applyDetection(quiet(30, SeriesState.NO_DATA, PAYMENT));          // 5 min later
        assertThat(only().status()).isEqualTo(IncidentStatus.ACTIVE);
        incidents.applyDetection(quiet(61, SeriesState.NO_DATA, PAYMENT));          // > 10 min
        assertThat(incidents.findActive()).isEmpty();
        IncidentSummary s = incidents.findSummary(id).orElseThrow();
        assertThat(s.resolutionReason()).isEqualTo("NO_DATA");
    }

    @Test
    void affectedEndpointComesFromEntryPointEndpointAnomaly() {
        var ep = new com.rootradar.apifd.anomaly.AnomalyEvent(
                "LATENCY|ENDPOINT|" + GATEWAY + "|POST /api/checkout||", AnomalyType.LATENCY, Severity.CRITICAL,
                GATEWAY, com.rootradar.apifd.telemetry.MetricScope.ENDPOINT, null, "POST", "/api/checkout", null,
                com.rootradar.apifd.anomaly.DetectionMethod.STATIC_THRESHOLD, "latency-p95-default", t(0),
                t(0).minusSeconds(20), t(0).minusSeconds(20), t(0), 2100.0, 50.0, 1500.0, null, 3, 3, 30, "slow",
                java.util.Map.of());
        incidents.applyDetection(detection(t(0), List.of(ep), assessment(GATEWAY, SeriesState.ANOMALOUS)));
        assertThat(only().affectedEndpoint()).isEqualTo("POST /api/checkout");
    }

    @Test
    void detailOfUnknownIncidentIsNotFound() {
        assertThatThrownBy(() -> incidents.getDetail(987654321L)).isInstanceOf(ApiException.NotFound.class);
        assertThatThrownBy(() -> incidents.timeline(987654321L)).isInstanceOf(ApiException.NotFound.class);
    }
}
