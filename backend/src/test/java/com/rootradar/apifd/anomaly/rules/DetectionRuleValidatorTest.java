package com.rootradar.apifd.anomaly.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootradar.apifd.anomaly.RuleMetric;
import com.rootradar.apifd.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import com.rootradar.apifd.anomaly.DetectionRuleSet;
import java.time.Instant;
import java.util.Optional;

class DetectionRuleValidatorTest {

    private static DetectionRuleEntity valid() {
        DetectionRuleEntity e = new DetectionRuleEntity();
        e.setRuleKey("k");
        e.setMetric(RuleMetric.LATENCY_P95_MS);
        e.setWarningThreshold(800.0);
        e.setCriticalThreshold(1500.0);
        return e;
    }

    @Test
    void validRulePasses() {
        assertThatCode(() -> DetectionRuleValidator.validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void warningAboveCriticalIsRejected() {
        DetectionRuleEntity e = valid();
        e.setWarningThreshold(2000.0);
        assertThatThrownBy(() -> DetectionRuleValidator.validate(e)).isInstanceOf(ApiException.BadRequest.class)
                .hasMessageContaining("warningThreshold");
    }

    @Test
    void errorRateThresholdsMustBeFractions() {
        DetectionRuleEntity e = valid();
        e.setMetric(RuleMetric.ERROR_RATE);
        assertThatThrownBy(() -> DetectionRuleValidator.validate(e)).hasMessageContaining("(0, 1]");
        e.setWarningThreshold(0.05);
        e.setCriticalThreshold(0.25);
        assertThatCode(() -> DetectionRuleValidator.validate(e)).doesNotThrowAnyException();
    }

    @Test
    void windowCountsAndNegativesAreRejected() {
        DetectionRuleEntity e = valid();
        e.setConsecutiveWindows(0);
        assertThatThrownBy(() -> DetectionRuleValidator.validate(e)).hasMessageContaining("consecutiveWindows");
        DetectionRuleEntity n = valid();
        n.setWarningThreshold(-1.0);
        assertThatThrownBy(() -> DetectionRuleValidator.validate(n)).hasMessageContaining(">= 0");
    }

    @Test
    void ruleWithNoCriterionIsRejected() {
        DetectionRuleEntity e = valid();
        e.setWarningThreshold(null);
        e.setCriticalThreshold(null);
        assertThatThrownBy(() -> DetectionRuleValidator.validate(e)).hasMessageContaining("at least one");
    }

    // ---- service-level behaviour with mocked collaborators (no Spring, no DB)

    private static MonitoredService svc(long id, String name) {
        return new MonitoredService(id, name, name, NodeType.HTTP_SERVICE, "http://localhost", "/h", "/t", 10, 1000,
                true, true, null, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void currentRulesResolvesServiceNamesAndFallsBackToDefaults() {
        DetectionRuleRepository repo = Mockito.mock(DetectionRuleRepository.class);
        ServiceRegistry reg = Mockito.mock(ServiceRegistry.class);
        Mockito.when(reg.findAll()).thenReturn(List.of(svc(3, "payment-service")));
        DetectionRuleService service = new DetectionRuleService(repo, reg);

        Mockito.when(repo.findAllByOrderByIdAsc()).thenReturn(List.of());
        assertThat(service.currentRules().rules()).hasSize(3);

        DetectionRuleEntity global = valid();
        DetectionRuleEntity specific = valid();
        specific.setRuleKey("p");
        specific.setServiceId(3L);
        DetectionRuleEntity orphan = valid();
        orphan.setRuleKey("o");
        orphan.setServiceId(99L);
        Mockito.when(repo.findAllByOrderByIdAsc()).thenReturn(List.of(global, specific, orphan));
        DetectionRuleSet set = service.currentRules();
        assertThat(set.rules()).hasSize(2);
        assertThat(set.resolve("payment-service", RuleMetric.LATENCY_P95_MS).orElseThrow().ruleKey()).isEqualTo("p");
        assertThat(set.resolve("order-service", RuleMetric.LATENCY_P95_MS).orElseThrow().ruleKey()).isEqualTo("k");
    }

    @Test
    void createRejectsDuplicatesAndUnknownService() {
        DetectionRuleRepository repo = Mockito.mock(DetectionRuleRepository.class);
        ServiceRegistry reg = Mockito.mock(ServiceRegistry.class);
        Mockito.when(reg.findById(7L)).thenReturn(Optional.empty());
        DetectionRuleService service = new DetectionRuleService(repo, reg);

        var req = new DetectionRuleRequest("k", RuleMetric.LATENCY_P95_MS, null, true, 800.0, 1500.0, null, null,
                null, null, null, null, null, null);
        Mockito.when(repo.findByRuleKey("k")).thenReturn(Optional.of(valid()));
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(ApiException.Conflict.class);

        var bad = new DetectionRuleRequest("k2", RuleMetric.LATENCY_P95_MS, 7L, true, 800.0, 1500.0, null, null,
                null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(bad)).isInstanceOf(ApiException.BadRequest.class)
                .hasMessageContaining("unknown serviceId");
    }

    @Test
    void updateMissingRuleIs404() {
        DetectionRuleRepository repo = Mockito.mock(DetectionRuleRepository.class);
        Mockito.when(repo.findById(5L)).thenReturn(Optional.empty());
        DetectionRuleService service = new DetectionRuleService(repo, Mockito.mock(ServiceRegistry.class));
        assertThatThrownBy(() -> service.update(5L, new DetectionRuleRequest(null, null, null, null, 1.0, 2.0, null,
                null, null, null, null, null, null, null))).isInstanceOf(ApiException.NotFound.class);
    }
}
