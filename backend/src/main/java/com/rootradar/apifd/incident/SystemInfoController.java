package com.rootradar.apifd.incident;

import com.rootradar.apifd.config.ApifdProperties;
import java.time.Clock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/system/info: lets the UI label demo mode and show when detection last ran. */
@RestController
@RequestMapping("/api/system")
public class SystemInfoController {
    static final String VERSION = "0.1.0";

    private final ApifdProperties props;
    private final DetectionPipeline pipeline;
    private final Clock clock;

    public SystemInfoController(ApifdProperties props, DetectionPipeline pipeline, Clock clock) {
        this.props = props;
        this.pipeline = pipeline;
        this.clock = clock;
    }

    @GetMapping("/info")
    public SystemInfo info() {
        return new SystemInfo(props.demoMode(), VERSION, clock.instant(), props.detection().windowSeconds(),
                props.detection().intervalSeconds(), pipeline.lastRunAt().orElse(null));
    }
}
