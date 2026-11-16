package com.rootradar.apifd.incident;

import com.rootradar.apifd.anomaly.AnomalyDetector;
import com.rootradar.apifd.anomaly.DetectionInput;
import com.rootradar.apifd.anomaly.DetectionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Scripted detector: returns whatever the supplied function builds and records every input it saw. */
public class FakeAnomalyDetector implements AnomalyDetector {
    public final List<DetectionInput> inputs = new ArrayList<>();
    public Function<DetectionInput, DetectionResult> script = in -> new DetectionResult(in.evaluatedAt(), List.of(), List.of());

    @Override
    public synchronized DetectionResult detect(DetectionInput input) {
        inputs.add(input);
        return script.apply(input);
    }
}
