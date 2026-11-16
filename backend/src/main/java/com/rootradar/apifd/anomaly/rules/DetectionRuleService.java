package com.rootradar.apifd.anomaly.rules;

import com.rootradar.apifd.anomaly.DetectionRule;
import com.rootradar.apifd.anomaly.DetectionRuleProvider;
import com.rootradar.apifd.anomaly.DetectionRuleSet;
import com.rootradar.apifd.anomaly.engine.DetectionDefaults;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads/writes {@code detection_rules}; also the {@link DetectionRuleProvider} used by the detection pipeline. */
@Service
public class DetectionRuleService implements DetectionRuleProvider {

    private final DetectionRuleRepository repository;
    private final ServiceRegistry registry;

    public DetectionRuleService(DetectionRuleRepository repository, ServiceRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    /** Current rules with service ids resolved to names. Falls back to built-in defaults if the table is empty. */
    @Override
    @Transactional(readOnly = true)
    public DetectionRuleSet currentRules() {
        List<DetectionRuleEntity> rows = repository.findAllByOrderByIdAsc();
        if (rows.isEmpty()) return DetectionDefaults.ruleSet();
        Map<Long, String> names = nameById();
        List<DetectionRule> rules = new ArrayList<>();
        for (DetectionRuleEntity e : rows) {
            String name = e.getServiceId() == null ? null : names.get(e.getServiceId());
            if (e.getServiceId() != null && name == null) continue; // orphan (service gone): ignore
            rules.add(toRule(e, name));
        }
        return new DetectionRuleSet(rules);
    }

    @Transactional(readOnly = true)
    public List<DetectionRuleResource> list(Long serviceId) {
        Map<Long, String> names = nameById();
        List<DetectionRuleEntity> rows = serviceId == null ? repository.findAllByOrderByIdAsc()
                : repository.findByServiceIdIsNullOrServiceIdOrderByIdAsc(serviceId);
        return rows.stream().map(e -> toResource(e, names)).toList();
    }

    @Transactional(readOnly = true)
    public DetectionRuleResource get(long id) {
        return toResource(find(id), nameById());
    }

    @Transactional
    public DetectionRuleResource create(DetectionRuleRequest req) {
        DetectionRuleEntity e = new DetectionRuleEntity();
        e.setRuleKey(req.ruleKey() == null ? null : req.ruleKey().trim());
        e.setMetric(req.metric());
        e.setServiceId(req.serviceId());
        apply(e, req, true);
        DetectionRuleValidator.validate(e);
        if (req.serviceId() != null && registry.findById(req.serviceId()).isEmpty()) {
            throw new ApiException.BadRequest("VALIDATION_FAILED", "unknown serviceId " + req.serviceId());
        }
        if (repository.findByRuleKey(e.getRuleKey()).isPresent()) {
            throw new ApiException.Conflict("CONFLICT", "ruleKey already exists: " + e.getRuleKey());
        }
        boolean dup = (req.serviceId() == null
                ? repository.findByMetricAndServiceIdIsNull(e.getMetric())
                : repository.findByMetricAndServiceId(e.getMetric(), req.serviceId())).isPresent();
        if (dup) throw new ApiException.Conflict("CONFLICT", "a rule for this metric and service already exists");
        return toResource(repository.save(e), nameById());
    }

    @Transactional
    public DetectionRuleResource update(long id, DetectionRuleRequest req) {
        DetectionRuleEntity e = find(id);
        apply(e, req, false);
        DetectionRuleValidator.validate(e);
        return toResource(repository.save(e), nameById());
    }

    @Transactional
    public void delete(long id) {
        repository.delete(find(id));
    }

    private DetectionRuleEntity find(long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException.NotFound("detection rule " + id + " not found"));
    }

    private static void apply(DetectionRuleEntity e, DetectionRuleRequest r, boolean create) {
        if (r.enabled() != null) e.setEnabled(r.enabled());
        e.setWarningThreshold(r.warningThreshold());
        e.setCriticalThreshold(r.criticalThreshold());
        e.setBaselineRatio(r.baselineRatio());
        e.setZScoreThreshold(r.zScoreThreshold());
        if (r.minRequests() != null) e.setMinRequests(r.minRequests());
        if (r.consecutiveWindows() != null) e.setConsecutiveWindows(r.consecutiveWindows());
        if (r.criticalConsecutiveWindows() != null) e.setCriticalConsecutiveWindows(r.criticalConsecutiveWindows());
        if (r.baselineWindows() != null) e.setBaselineWindows(r.baselineWindows());
        if (r.minBaselineWindows() != null) e.setMinBaselineWindows(r.minBaselineWindows());
        if (r.recoveryWindows() != null) e.setRecoveryWindows(r.recoveryWindows());
    }

    private Map<Long, String> nameById() {
        Map<Long, String> m = new HashMap<>();
        for (MonitoredService s : registry.findAll()) m.put(s.id(), s.name());
        return m;
    }

    static DetectionRule toRule(DetectionRuleEntity e, String serviceName) {
        return new DetectionRule(e.getRuleKey(), e.getMetric(), serviceName, e.isEnabled(), e.getWarningThreshold(),
                e.getCriticalThreshold(), e.getBaselineRatio(), e.getZScoreThreshold(), e.getMinRequests(),
                e.getConsecutiveWindows(), e.getCriticalConsecutiveWindows(), e.getBaselineWindows(),
                e.getMinBaselineWindows(), e.getRecoveryWindows());
    }

    static DetectionRuleResource toResource(DetectionRuleEntity e, Map<Long, String> names) {
        return new DetectionRuleResource(e.getId(), e.getRuleKey(), e.getMetric(), e.getServiceId(),
                e.getServiceId() == null ? null : names.get(e.getServiceId()), e.isEnabled(), e.getWarningThreshold(),
                e.getCriticalThreshold(), e.getBaselineRatio(), e.getZScoreThreshold(), e.getMinRequests(),
                e.getConsecutiveWindows(), e.getCriticalConsecutiveWindows(), e.getBaselineWindows(),
                e.getMinBaselineWindows(), e.getRecoveryWindows(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
