package com.rootradar.apifd.incident;

import com.rootradar.apifd.anomaly.AnomalyEventView;
import com.rootradar.apifd.common.ApiException;
import com.rootradar.apifd.common.PageResponse;
import com.rootradar.apifd.common.Severity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API for incidents, see docs/API_CONTRACTS.md section 5. */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {
    private final IncidentService incidents;

    public IncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    public PageResponse<IncidentSummary> list(@RequestParam(required = false) List<IncidentStatus> status,
                                              @RequestParam(required = false) Severity severity,
                                              @RequestParam(required = false) String service,
                                              @RequestParam(required = false) Instant from,
                                              @RequestParam(required = false) Instant to,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        if (page < 0) throw new ApiException.BadRequest("BAD_REQUEST", "page must be >= 0");
        if (size < 1) throw new ApiException.BadRequest("BAD_REQUEST", "size must be >= 1");
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200),
                Sort.by(Sort.Order.desc("detectedAt"), Sort.Order.desc("id")));
        return PageResponse.of(incidents.search(new IncidentFilter(status, severity, service, from, to), pageable));
    }

    @GetMapping("/active")
    public List<IncidentSummary> active() {
        return incidents.findActive();
    }

    @GetMapping("/{id}")
    public IncidentDetail detail(@PathVariable long id) {
        return incidents.getDetail(id);
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntry> timeline(@PathVariable long id) {
        return incidents.timeline(id);
    }

    @GetMapping("/{id}/diagnosis")
    public DiagnosisView diagnosis(@PathVariable long id) {
        return incidents.latestDiagnosis(id)
                .orElseThrow(() -> new ApiException.NotFound("Incident " + id + " has no diagnosis yet"));
    }

    @GetMapping("/{id}/diagnoses")
    public List<DiagnosisView> diagnoses(@PathVariable long id) {
        return incidents.diagnoses(id);
    }

    @GetMapping("/{id}/anomalies")
    public List<AnomalyEventView> anomalies(@PathVariable long id) {
        return incidents.anomalies(id);
    }
}
