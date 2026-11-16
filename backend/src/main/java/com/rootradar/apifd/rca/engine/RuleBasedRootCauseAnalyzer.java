package com.rootradar.apifd.rca.engine;

import static com.rootradar.apifd.rca.engine.ScoringModel.*;

import com.rootradar.apifd.anomaly.AnomalyEvent;
import com.rootradar.apifd.anomaly.AnomalyType;
import com.rootradar.apifd.anomaly.SeriesState;
import com.rootradar.apifd.common.Severity;
import com.rootradar.apifd.graph.DependencyGraph;
import com.rootradar.apifd.graph.GraphNode;
import com.rootradar.apifd.monitoring.HealthStatus;
import com.rootradar.apifd.monitoring.NodeType;
import com.rootradar.apifd.rca.DiagnosisInput;
import com.rootradar.apifd.rca.DiagnosisResult;
import com.rootradar.apifd.rca.FindingClassification;
import com.rootradar.apifd.rca.RootCauseAnalyzer;
import com.rootradar.apifd.rca.ScoreFactor;
import com.rootradar.apifd.rca.ServiceFinding;
import com.rootradar.apifd.rca.ServiceObservation;
import com.rootradar.apifd.telemetry.MetricScope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure, deterministic, rule-based root-cause analyzer. Scores are sums of documented rule factors
 * (see {@link ScoringModel} and docs/RCA_SCORING_MODEL.md); they are ranking scores, NOT probabilities.
 */
@Component
public class RuleBasedRootCauseAnalyzer implements RootCauseAnalyzer {

    private final Duration tolerance;

    public RuleBasedRootCauseAnalyzer() {
        this(ONSET_TOLERANCE);
    }

    public RuleBasedRootCauseAnalyzer(Duration onsetTolerance) {
        this.tolerance = onsetTolerance;
    }

    // ------------------------------------------------------------------ internal types

    private record Node(String name, NodeType type, ServiceObservation obs, List<AnomalyEvent> own, boolean abnormal,
                        Instant onset, SeriesState state) {
        boolean normal() { return !abnormal && state == SeriesState.NORMAL; }
    }

    private static final class Scored {
        final Node node;
        int score;
        List<ScoreFactor> factors = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        boolean explained;          // an abnormal downstream (timing-compatible) accounts for this node's behaviour
        FindingClassification cls;
        Scored(Node n) { this.node = n; }
    }

    // ------------------------------------------------------------------ entry point

    @Override
    public DiagnosisResult analyze(DiagnosisInput input) {
        DependencyGraph graph = input.graph() == null ? DependencyGraph.empty() : input.graph();
        List<String> caveats = new ArrayList<>();
        caveats.add("Ranking is correlation-based (timing + dependency structure), not proof of causation.");
        caveats.add("Scores are explainable ranking scores out of 100, not probabilities.");

        // ---- build nodes (unknown names ignored and reported)
        Map<String, ServiceObservation> obsByName = new LinkedHashMap<>();
        if (input.observations() != null) {
            for (ServiceObservation o : input.observations()) {
                if (o != null && graph.contains(o.serviceName())) obsByName.putIfAbsent(o.serviceName(), o);
            }
        }
        Map<String, List<AnomalyEvent>> anomsBySubject = new LinkedHashMap<>();
        Set<String> unknown = new java.util.TreeSet<>();
        List<AnomalyEvent> allAnoms = new ArrayList<>();
        if (input.anomalies() != null) {
            for (AnomalyEvent a : input.anomalies()) {
                if (a == null) continue;
                if (a.subjectService() == null || !graph.contains(a.subjectService())) {
                    unknown.add(String.valueOf(a.subjectService()));
                    continue;
                }
                anomsBySubject.computeIfAbsent(a.subjectService(), k -> new ArrayList<>()).add(a);
                allAnoms.add(a);
            }
        }
        if (!unknown.isEmpty()) {
            caveats.add("Ignored anomalies for services not in the dependency graph: " + unknown + ".");
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (GraphNode gn : graph.nodes()) {
            ServiceObservation o = obsByName.get(gn.name());
            List<AnomalyEvent> own = anomsBySubject.getOrDefault(gn.name(), List.of());
            SeriesState st = o == null || o.state() == null ? SeriesState.NO_DATA : o.state();
            boolean abnormal = !own.isEmpty() || st == SeriesState.ANOMALOUS
                    || (o != null && o.healthStatus() == HealthStatus.DOWN);
            nodes.put(gn.name(), new Node(gn.name(), gn.nodeType(), o, own, abnormal, onsetOf(own, o), st));
        }

        if (nodes.values().stream().noneMatch(Node::abnormal)) {
            return new DiagnosisResult(input.analyzedAt(), MODEL_VERSION, null, null, false,
                    "No open anomalies in the analysed graph; no root-cause analysis is possible.", List.of(), caveats);
        }

        // ---- involved set: abnormal nodes + non-normal (unjudgeable) nodes connected to an abnormal node
        List<Node> abnormalNodes = nodes.values().stream().filter(Node::abnormal).toList();
        Set<String> abnormalNames = new LinkedHashSet<>();
        abnormalNodes.forEach(n -> abnormalNames.add(n.name()));
        List<Node> unjudged = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.abnormal() || n.normal()) continue;
            boolean connected = abnormalNames.stream().anyMatch(a -> graph.hasPath(a, n.name()) || graph.hasPath(n.name(), a));
            if (connected) unjudged.add(n);
        }

        // ---- score
        List<Scored> scored = new ArrayList<>();
        for (Node n : abnormalNodes) scored.add(score(n, nodes, graph, abnormalNames));
        for (Node n : unjudged) scored.add(unjudgedFinding(n));

        // deterministic order
        Comparator<Scored> order = Comparator
                .comparingInt((Scored s) -> -s.score)
                .thenComparing((Scored s) -> s.node.onset(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(s -> graph.downstream(s.node.name()).size())
                .thenComparing(s -> s.node.name());
        scored.sort(order);

        // ---- classification
        for (Scored s : scored) {
            if (!s.node.abnormal()) s.cls = FindingClassification.INSUFFICIENT_EVIDENCE;
            else if (s.explained) s.cls = FindingClassification.AFFECTED;
            else s.cls = null; // decided per group below
        }
        List<Scored> candidates = scored.stream().filter(s -> s.node.abnormal() && !s.explained).toList();
        List<List<Scored>> groups = groupByPath(candidates, graph);
        List<Scored> groupTops = new ArrayList<>();
        List<String> ambiguityNotes = new ArrayList<>();
        for (List<Scored> g : groups) {
            g.sort(order);
            Scored top = g.get(0);
            boolean topProbable = top.score >= PROBABLE_CAUSE_MIN;
            Set<Scored> tied = new HashSet<>();
            if (topProbable) {
                for (Scored o : g.subList(1, g.size())) {
                    if (top.score - o.score <= TIE_MARGIN && o.score >= CONTRIBUTING_MIN) tied.add(o);
                }
            }
            for (Scored s : g) {
                if (s == top && topProbable) s.cls = FindingClassification.PROBABLE_CAUSE;
                else if (tied.contains(s)) s.cls = s.score >= PROBABLE_CAUSE_MIN
                        ? FindingClassification.PROBABLE_CAUSE : FindingClassification.CONTRIBUTING_FACTOR;
                else if (s.score >= CONTRIBUTING_MIN) s.cls = FindingClassification.CONTRIBUTING_FACTOR;
                else s.cls = FindingClassification.INSUFFICIENT_EVIDENCE;
            }
            if (topProbable) groupTops.add(top);
            if (!tied.isEmpty()) {
                List<String> names = new ArrayList<>();
                names.add(top.node.name() + " (" + top.score + ")");
                tied.stream().sorted(order).forEach(t -> names.add(t.node.name() + " (" + t.score + ")"));
                ambiguityNotes.add(String.join(" and ", names));
            }
        }

        // ---- ranks + findings
        List<ServiceFinding> findings = new ArrayList<>();
        int rank = 1;
        for (Scored s : scored) {
            findings.add(new ServiceFinding(s.node.name(), rank++, s.cls, s.score, s.node.onset(),
                    List.copyOf(s.factors), List.copyOf(s.evidence)));
        }

        // ---- caveats
        for (Node n : nodes.values()) {
            if (n.type() == NodeType.DEPENDENCY_ONLY && (n.abnormal() || isDownstreamOfAbnormal(n, abnormalNames, graph))) {
                caveats.add("'" + n.name() + "' has no direct health check; its status is derived from caller-side measurements only.");
            }
        }
        for (Scored s : scored) {
            if (!s.node.abnormal()) {
                caveats.add("No usable telemetry/baseline for '" + s.node.name() + "' (" + s.node.state()
                        + "); it can neither be ruled in nor out.");
            } else if (s.node.state() == SeriesState.NO_DATA) {
                caveats.add("'" + s.node.name() + "' has no telemetry of its own; its assessment relies on caller observations/health checks.");
            }
        }

        // ---- verdict
        String probable = null;
        boolean conclusive = false;
        boolean anyAmbiguity = !ambiguityNotes.isEmpty();
        if (!ambiguityNotes.isEmpty()) {
            caveats.add("Ambiguous: " + String.join("; ", ambiguityNotes) + " cannot be separated (score difference <= "
                    + TIE_MARGIN + " points). No single cause is named.");
        }
        if (groupTops.size() == 1 && !anyAmbiguity) {
            probable = groupTops.get(0).node.name();
            conclusive = true;
        } else if (groupTops.size() > 1) {
            List<Scored> byScore = new ArrayList<>(groupTops);
            byScore.sort(order);
            caveats.add("Multiple independent fault groups detected (" + String.join(", ",
                    byScore.stream().map(s -> s.node.name()).toList())
                    + "); they are not connected by a dependency path, so they are reported as separate probable causes.");
            if (!anyAmbiguity && byScore.get(0).score - byScore.get(1).score > TIE_MARGIN) {
                probable = byScore.get(0).node.name();
            }
        }
        Integer topScore = findings.isEmpty() ? null : findings.get(0).score();
        String summary = summarize(findings, groupTops, probable, conclusive, ambiguityNotes);
        return new DiagnosisResult(input.analyzedAt(), MODEL_VERSION, probable, topScore, conclusive, summary,
                List.copyOf(findings), List.copyOf(caveats));
    }

    private static boolean isDownstreamOfAbnormal(Node n, Set<String> abnormal, DependencyGraph g) {
        return abnormal.stream().anyMatch(a -> g.hasPath(a, n.name()));
    }

    // ------------------------------------------------------------------ scoring

    private Scored score(Node n, Map<String, Node> nodes, DependencyGraph graph, Set<String> abnormalNames) {
        Scored s = new Scored(n);
        ServiceObservation o = n.obs();
        List<AnomalyEvent> latency = n.own().stream().filter(a -> a.type() == AnomalyType.LATENCY).toList();
        List<AnomalyEvent> errors = n.own().stream()
                .filter(a -> a.type() == AnomalyType.ERROR_RATE || a.type() == AnomalyType.HEALTH_CHECK_FAILURE).toList();
        boolean healthDown = o != null && o.healthStatus() == HealthStatus.DOWN;

        // 1 latency
        {
            int pts = 0;
            String detail = "no latency anomaly";
            if (!latency.isEmpty()) {
                boolean crit = latency.stream().anyMatch(a -> a.severity() == Severity.CRITICAL);
                pts = crit ? LATENCY_MAX : LATENCY_WARNING;
                AnomalyEvent w = worst(latency);
                detail = (crit ? "CRITICAL" : "WARNING") + " latency anomaly: " + w.description();
                s.evidence.add("Latency anomaly on " + n.name() + ": " + w.description());
            }
            s.factors.add(new ScoreFactor("LATENCY_ANOMALY", "Latency anomaly", pts, LATENCY_MAX, pts > 0, detail));
        }
        // 2 errors
        {
            int pts = 0;
            String detail = "no error-rate / health-check anomaly";
            if (!errors.isEmpty() || healthDown) {
                boolean crit = healthDown || errors.stream().anyMatch(a -> a.severity() == Severity.CRITICAL);
                pts = crit ? ERROR_MAX : ERROR_WARNING;
                String d = errors.isEmpty() ? "health check reports DOWN" : worst(errors).description();
                detail = (crit ? "CRITICAL" : "WARNING") + " error signal: " + d;
                s.evidence.add("Error signal on " + n.name() + ": " + d);
            }
            s.factors.add(new ScoreFactor("ERROR_RATE_ANOMALY", "Error-rate / health anomaly", pts, ERROR_MAX, pts > 0, detail));
        }
        // 3 onset
        {
            Set<String> related = new LinkedHashSet<>();
            for (String a : abnormalNames) {
                if (!a.equals(n.name()) && (graph.hasPath(a, n.name()) || graph.hasPath(n.name(), a))) related.add(a);
            }
            int pts;
            String detail;
            if (related.isEmpty()) {
                pts = ONSET_MAX;
                detail = "no other anomalous service is connected by a dependency path; nothing started earlier";
            } else if (n.onset() == null) {
                pts = 0;
                detail = "first-abnormal time unknown, cannot compare with related anomalous services " + related;
            } else {
                Instant min = n.onset();
                for (String r : related) {
                    Instant ro = nodes.get(r).onset();
                    if (ro != null && ro.isBefore(min)) min = ro;
                }
                Duration delta = Duration.between(min, n.onset());
                if (delta.compareTo(tolerance) <= 0) {
                    pts = ONSET_MAX;
                    detail = delta.isZero() ? "first among connected anomalous services " + related
                            : "started " + delta.toSeconds() + " s after the earliest related anomaly (within " + tolerance.toSeconds() + " s tolerance)";
                } else if (delta.compareTo(tolerance.multipliedBy(ONSET_PARTIAL_MULTIPLIER)) <= 0) {
                    pts = ONSET_PARTIAL;
                    detail = "started " + delta.toSeconds() + " s after the earliest related anomaly (beyond tolerance, partial credit)";
                } else {
                    pts = 0;
                    detail = "started " + delta.toSeconds() + " s after the earliest related anomaly; likely a later consequence";
                }
                s.evidence.add("First abnormal at " + n.onset() + "; " + detail + ".");
            }
            s.factors.add(new ScoreFactor("EARLIEST_ONSET", "Earliest onset among connected anomalies", pts, ONSET_MAX, pts > 0, detail));
        }
        // 4 downstream healthy
        {
            Set<String> direct = graph.directDownstream(n.name());
            int pts;
            String detail;
            if (direct.isEmpty()) {
                pts = DOWNSTREAM_MAX;
                detail = "no downstream dependencies (leaf); the problem cannot lie further down";
                s.evidence.add(n.name() + " has no downstream dependencies in the graph, so the fault cannot be further down.");
            } else {
                int healthy = 0;
                boolean anyAbnormal = false;
                List<String> parts = new ArrayList<>();
                for (String d : direct) {
                    Node dn = nodes.get(d);
                    if (dn.abnormal()) {
                        anyAbnormal = true;
                        parts.add(d + " ABNORMAL");
                    } else if (dn.normal()) {
                        healthy++;
                        parts.add(d + " NORMAL");
                        s.evidence.add("Downstream '" + d + "' is NORMAL" + describeHealthyMetrics(dn)
                                + (dn.type() == NodeType.DEPENDENCY_ONLY ? " (measured by its callers; no direct health check)" : "")
                                + ", so " + n.name() + "'s behaviour is not explained by it.");
                    } else {
                        parts.add(d + " " + dn.state() + " (cannot be judged)");
                        s.evidence.add("Downstream '" + d + "' has no usable telemetry (" + dn.state() + "); it cannot be ruled out.");
                    }
                }
                pts = anyAbnormal ? 0 : Math.round(DOWNSTREAM_MAX * (float) healthy / direct.size());
                detail = String.join(", ", parts);
            }
            s.factors.add(new ScoreFactor("DOWNSTREAM_HEALTHY", "Downstream dependencies healthy", pts, DOWNSTREAM_MAX, pts > 0, detail));
        }
        // 5 upstream impact
        {
            Set<String> up = graph.upstream(n.name());
            int pts = 0;
            String detail;
            if (up.isEmpty()) {
                detail = "entry point: no callers that could show impact";
            } else {
                List<String> hit = up.stream().filter(abnormalNames::contains).toList();
                pts = Math.round(UPSTREAM_MAX * (float) hit.size() / up.size());
                detail = hit.size() + " of " + up.size() + " upstream callers abnormal " + hit;
                if (!hit.isEmpty()) s.evidence.add("Impact propagates upstream: " + hit + " are also abnormal.");
            }
            s.factors.add(new ScoreFactor("UPSTREAM_IMPACT", "Impact propagated to callers", pts, UPSTREAM_MAX, pts > 0, detail));
        }
        // 6 magnitude
        {
            Double ratio = latencyRatio(n, latency);
            Double delta = errorDelta(n, errors);
            int lp = ratio == null ? 0 : ladder(ratio, RATIO_STEPS, RATIO_POINTS);
            int ep = delta == null ? 0 : ladder(delta, ERROR_DELTA_STEPS, ERROR_DELTA_POINTS);
            int pts = Math.max(lp, ep);
            List<String> d = new ArrayList<>();
            if (ratio != null) d.add(String.format(Locale.ROOT, "latency %.1fx baseline (+%d)", ratio, lp));
            if (delta != null) d.add(String.format(Locale.ROOT, "error rate +%.1f pp vs baseline (+%d)", delta * 100, ep));
            String detail = d.isEmpty() ? "no baseline available to measure magnitude" : String.join("; ", d) + "; the larger counts";
            if (pts > 0) s.evidence.add("Magnitude: " + String.join("; ", d) + ".");
            s.factors.add(new ScoreFactor("MAGNITUDE", "Magnitude relative to baseline", pts, MAGNITUDE_MAX, pts > 0, detail));
        }
        // 7 caller corroboration
        {
            List<String> observers = n.own().stream()
                    .filter(a -> a.scope() == MetricScope.DEPENDENCY_CALL && a.observedBy() != null
                            && !a.observedBy().equals(n.name()) && graph.contains(a.observedBy()))
                    .map(AnomalyEvent::observedBy).distinct().sorted().toList();
            int pts = observers.isEmpty() ? 0 : CORROBORATION_MAX;
            String detail = observers.isEmpty() ? "no caller measured " + n.name() + " as anomalous"
                    : "independently measured as anomalous by caller(s) " + observers;
            if (pts > 0) s.evidence.add(n.name() + " was independently observed as anomalous by " + observers + ".");
            s.factors.add(new ScoreFactor("CALLER_CORROBORATION", "Corroborated by caller measurements", pts, CORROBORATION_MAX, pts > 0, detail));
        }
        // 8 evidence quality
        {
            boolean baseline = o != null && (o.baselineLatencyP95Ms() != null || o.baselineErrorRate() != null);
            int pts = 0;
            String detail;
            if (n.state() == SeriesState.ANOMALOUS && baseline) {
                pts = QUALITY_MAX;
                detail = "own series assessed ANOMALOUS against a sufficient baseline";
            } else if (n.state() == SeriesState.ANOMALOUS) {
                pts = 2;
                detail = "own series ANOMALOUS, but judged without a baseline";
            } else {
                detail = "own series state " + n.state() + "; evidence rests on other sources";
            }
            s.factors.add(new ScoreFactor("EVIDENCE_QUALITY", "Evidence quality", pts, QUALITY_MAX, pts > 0, detail));
        }
        // penalties
        {
            List<String> abnormalDown = graph.downstream(n.name()).stream().filter(abnormalNames::contains).toList();
            List<String> explainers = new ArrayList<>();
            for (String d : abnormalDown) {
                Instant dOn = nodes.get(d).onset();
                if (dOn == null || n.onset() == null || !dOn.isAfter(n.onset().plus(tolerance))) explainers.add(d);
            }
            int pts = 0;
            String detail = "no anomalous downstream dependency";
            if (!explainers.isEmpty()) {
                pts = PENALTY_DOWNSTREAM_EXPLAINS;
                s.explained = true;
                detail = "downstream " + explainers + " is anomalous with compatible timing; " + n.name() + " is likely affected, not the origin";
                s.evidence.add("Anomalous downstream " + explainers + " (not later than " + n.name()
                        + " beyond tolerance) can explain this behaviour.");
            } else if (!abnormalDown.isEmpty()) {
                pts = PENALTY_DOWNSTREAM_LATER;
                detail = "downstream " + abnormalDown + " became anomalous clearly AFTER " + n.name()
                        + "; ambiguous (own fault with later propagation, or independent)";
                s.evidence.add("Downstream " + abnormalDown + " became abnormal only after " + n.name() + " did.");
            }
            s.factors.add(new ScoreFactor("DOWNSTREAM_ANOMALOUS", "Penalty: anomalous downstream", pts, 0, pts < 0, detail));
        }
        {
            boolean none = n.state() == SeriesState.NO_DATA && !(o != null && o.healthStatus() == HealthStatus.DOWN);
            int pts = none ? PENALTY_NO_TELEMETRY : 0;
            String detail = none ? "no telemetry of its own; evidence is indirect" : "own telemetry available";
            s.factors.add(new ScoreFactor("NO_OWN_TELEMETRY", "Penalty: missing own telemetry", pts, 0, pts < 0, detail));
        }
        int sum = s.factors.stream().mapToInt(ScoreFactor::points).sum();
        s.score = Math.max(0, Math.min(100, sum));
        return s;
    }

    private Scored unjudgedFinding(Node n) {
        Scored s = new Scored(n);
        String why = n.state() == SeriesState.INSUFFICIENT_BASELINE
                ? "baseline insufficient to judge" : "no telemetry in the evaluation span";
        for (String[] r : new String[][] {
                {"LATENCY_ANOMALY", "Latency anomaly"}, {"ERROR_RATE_ANOMALY", "Error-rate / health anomaly"},
                {"EARLIEST_ONSET", "Earliest onset among connected anomalies"}, {"DOWNSTREAM_HEALTHY", "Downstream dependencies healthy"},
                {"UPSTREAM_IMPACT", "Impact propagated to callers"}, {"MAGNITUDE", "Magnitude relative to baseline"},
                {"CALLER_CORROBORATION", "Corroborated by caller measurements"}, {"EVIDENCE_QUALITY", "Evidence quality"}}) {
            int max = switch (r[0]) {
                case "LATENCY_ANOMALY" -> LATENCY_MAX; case "ERROR_RATE_ANOMALY" -> ERROR_MAX;
                case "EARLIEST_ONSET" -> ONSET_MAX; case "DOWNSTREAM_HEALTHY" -> DOWNSTREAM_MAX;
                case "UPSTREAM_IMPACT" -> UPSTREAM_MAX; case "MAGNITUDE" -> MAGNITUDE_MAX;
                case "CALLER_CORROBORATION" -> CORROBORATION_MAX; default -> QUALITY_MAX;
            };
            s.factors.add(new ScoreFactor(r[0], r[1], 0, max, false, "not assessable: " + why));
        }
        s.evidence.add(n.name() + " cannot be judged (" + why + "); it is connected to anomalous services and is neither ruled in nor out.");
        s.score = 0;
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private static Instant onsetOf(List<AnomalyEvent> own, ServiceObservation o) {
        Instant min = null;
        for (AnomalyEvent a : own) {
            if (a.firstBreachAt() != null && (min == null || a.firstBreachAt().isBefore(min))) min = a.firstBreachAt();
        }
        if (o != null && o.firstAbnormalAt() != null && (min == null || o.firstAbnormalAt().isBefore(min))) {
            min = o.firstAbnormalAt();
        }
        return min;
    }

    private static AnomalyEvent worst(List<AnomalyEvent> list) {
        return list.stream().sorted(Comparator
                .comparing((AnomalyEvent a) -> a.severity() == Severity.CRITICAL ? 0 : 1)
                .thenComparing(a -> a.fingerprint() == null ? "" : a.fingerprint())).findFirst().orElseThrow();
    }

    private static Double latencyRatio(Node n, List<AnomalyEvent> latency) {
        Double best = null;
        ServiceObservation o = n.obs();
        if (o != null && o.currentLatencyP95Ms() != null && o.baselineLatencyP95Ms() != null && o.baselineLatencyP95Ms() > 0) {
            best = o.currentLatencyP95Ms() / o.baselineLatencyP95Ms();
        }
        for (AnomalyEvent a : latency) {
            if (a.baselineValue() != null && a.baselineValue() > 0) {
                double r = a.observedValue() / a.baselineValue();
                if (best == null || r > best) best = r;
            }
        }
        return best;
    }

    private static Double errorDelta(Node n, List<AnomalyEvent> errors) {
        Double best = null;
        ServiceObservation o = n.obs();
        if (o != null && o.currentErrorRate() != null && o.baselineErrorRate() != null) {
            best = o.currentErrorRate() - o.baselineErrorRate();
        }
        for (AnomalyEvent a : errors) {
            if (a.type() == AnomalyType.ERROR_RATE && a.baselineValue() != null) {
                double d = a.observedValue() - a.baselineValue();
                if (best == null || d > best) best = d;
            }
        }
        return best;
    }

    private static int ladder(double v, double[] steps, int[] pts) {
        for (int i = 0; i < steps.length; i++) if (v >= steps[i]) return pts[i];
        return 0;
    }

    private static String describeHealthyMetrics(Node d) {
        ServiceObservation o = d.obs();
        if (o == null) return "";
        if (o.currentLatencyP95Ms() != null && o.baselineLatencyP95Ms() != null) {
            return String.format(Locale.ROOT, " (p95 %.0f ms vs baseline %.0f ms)", o.currentLatencyP95Ms(), o.baselineLatencyP95Ms());
        }
        if (o.currentLatencyP95Ms() != null) return String.format(Locale.ROOT, " (p95 %.0f ms)", o.currentLatencyP95Ms());
        return "";
    }

    /** Groups candidates into sets connected through dependency paths (union-find; deterministic by input order). */
    private static List<List<Scored>> groupByPath(List<Scored> cands, DependencyGraph graph) {
        int n = cands.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                String a = cands.get(i).node.name();
                String b = cands.get(j).node.name();
                if (graph.hasPath(a, b) || graph.hasPath(b, a)) union(parent, i, j);
            }
        }
        Map<Integer, List<Scored>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(cands.get(i));
        return new ArrayList<>(byRoot.values());
    }

    private static int find(int[] p, int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }

    private static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

    private static String summarize(List<ServiceFinding> findings, List<Scored> tops, String probable,
                                    boolean conclusive, List<String> ambiguity) {
        if (probable != null && conclusive) {
            ServiceFinding f = findings.stream().filter(x -> x.serviceName().equals(probable)).findFirst().orElseThrow();
            List<String> affected = findings.stream().filter(x -> x.classification() == FindingClassification.AFFECTED)
                    .map(ServiceFinding::serviceName).toList();
            return probable + " is the probable cause (score " + f.score() + "/100, a ranking score and not a probability)"
                    + (affected.isEmpty() ? "." : "; " + affected + " look affected by it.");
        }
        if (!ambiguity.isEmpty()) {
            return "Inconclusive: " + String.join("; ", ambiguity) + " cannot be separated; more evidence is needed.";
        }
        if (tops.size() > 1) {
            return "Multiple independent probable causes: " + tops.stream().map(t -> t.node.name() + " (" + t.score + ")").toList()
                    + "; they are not connected by a dependency path.";
        }
        return "No service reaches the probable-cause threshold (" + PROBABLE_CAUSE_MIN + "); evidence is insufficient to name a root cause.";
    }
}
