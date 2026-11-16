package com.rootradar.apifd.anomaly.store;

import com.rootradar.apifd.anomaly.AnomalyEventView;
import com.rootradar.apifd.common.PageResponse;
import com.rootradar.apifd.incident.IncidentService;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/anomalies (history of persisted anomalies, newest first). */
@RestController
@RequestMapping("/api/anomalies")
public class AnomalyController {
    private final IncidentService incidents;

    public AnomalyController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    public PageResponse<AnomalyEventView> list(@RequestParam(required = false) String service,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Instant from,
                                               @RequestParam(required = false) Instant to,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)),
                Sort.by(Sort.Order.desc("detectedAt"), Sort.Order.desc("id")));
        return PageResponse.of(incidents.searchAnomalies(service, from, to, status, pageable));
    }
}
