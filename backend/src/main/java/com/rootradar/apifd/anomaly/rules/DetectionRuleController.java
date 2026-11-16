package com.rootradar.apifd.anomaly.rules;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/detection-rules")
public class DetectionRuleController {

    private final DetectionRuleService service;

    public DetectionRuleController(DetectionRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<DetectionRuleResource> list(@RequestParam(required = false) Long serviceId) {
        return service.list(serviceId);
    }

    @GetMapping("/{id}")
    public DetectionRuleResource get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DetectionRuleResource create(@RequestBody DetectionRuleRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public DetectionRuleResource update(@PathVariable long id, @RequestBody DetectionRuleRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
