package dev.marwan.console.agent;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.marwan.console.cluster.KubernetesAccess;
import dev.marwan.console.metrics.ChartName;
import dev.marwan.console.metrics.RangeQuery;
import dev.marwan.console.metrics.Series;
import dev.marwan.console.objects.LogLine;
import dev.marwan.console.objects.LogLines;
import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.objects.Scope;
import tools.jackson.databind.JsonNode;

/**
 * What the model may look at: five read-only questions about the run.
 *
 * Every answer is bounded - 40 log lines, 30 points a series, 20 events - so a
 * curious model cannot fill its own context or the stored report, and every
 * answer becomes a fact the report can cite. A bad request is answered too,
 * with a fact saying what was wrong, because an exception here would end the
 * investigation over a typo the model could have corrected.
 */
public class Tools {

    static final int LOG_LINES = 40;
    static final int POINTS = 30;
    static final int EVENTS = 20;
    static final int MAX_VALUE = 4000;
    static final Set<String> KINDS = Set.of("Deployment", "Pod", "HorizontalPodAutoscaler", "Job", "Service");

    /** The menu the model is shown, one line per tool. */
    public static final String MENU = """
            pod_logs {"pod": name, "level": "WARN"|"ERROR"|"INFO" (optional), "contains": text (optional)} - up to 40 log lines from the run window
            metric {"chart": "requests"|"latency"|"pool"|"replicas", "pod": name (optional)} - one chart over the run window, 30 points a series
            events {"kind": "Deployment"|"Pod"|"HorizontalPodAutoscaler"|"Job"|"Service", "name": name} - Kubernetes events for one object in the window
            pod_status {"pod": name} - phase, readiness, restarts, node, owning ReplicaSet
            endpoints {"service": name} - the pods ready behind a Service now
            """;

    private final ObjectSource objects;
    private final RangeQuery prometheus;

    public Tools(ObjectSource objects, RangeQuery prometheus) {
        this.objects = objects;
        this.prometheus = prometheus;
    }

    public Fact call(String tool, JsonNode args, RunWindow w, Facts facts) {
        String value;
        try {
            value = switch (tool == null ? "" : tool) {
                case "pod_logs" -> podLogs(args, w);
                case "metric" -> metric(args, w);
                case "events" -> events(args, w);
                case "pod_status" -> podStatus(args);
                case "endpoints" -> endpoints(args);
                default -> "unknown tool '" + tool + "'; the tools are pod_logs, metric, events, pod_status, endpoints";
            };
        } catch (Refused e) {
            value = "refused: " + e.getMessage() + " is not part of this project";
        } catch (BadArguments e) {
            value = "bad arguments: " + e.getMessage();
        } catch (Exception e) {
            value = "unavailable: " + KubernetesAccess.summarise(e);
        }
        String bounded = value.length() > MAX_VALUE ? value.substring(0, MAX_VALUE) + "…" : value;
        return facts.add("tool: " + tool, LogLines.mask(describe(tool, args)), LogLines.mask(bounded));
    }

    private String podLogs(JsonNode args, RunWindow w) {
        Pod pod = ourPod(required(args, "pod"));
        String level = optional(args, "level").map(s -> s.toUpperCase(Locale.ROOT)).orElse(null);
        String contains = optional(args, "contains").orElse(null);
        List<LogLine> lines = LogLines.parse(objects.podLog(pod.getMetadata().getName(), 500)).stream()
                .filter(l -> Baseline.inWindow(l, w))
                .filter(l -> level == null || level.equals(l.level()))
                .filter(l -> contains == null || (l.message() != null && l.message().contains(contains)))
                .toList();
        if (lines.isEmpty()) {
            return "no matching lines in the run window";
        }
        return lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()).stream()
                .map(l -> l.at().substring(Math.min(11, l.at().length()), Math.min(19, l.at().length()))
                        + " " + (l.level() == null ? "" : l.level() + " ") + l.message())
                .collect(Collectors.joining("\n"));
    }

    private String metric(JsonNode args, RunWindow w) throws Exception {
        ChartName chart = ChartName.parse(required(args, "chart"))
                .orElseThrow(() -> new BadArguments("chart must be requests, latency, pool or replicas"));
        Optional<String> pod = optional(args, "pod");
        List<Series> series = prometheus.range(chart.promql(), chart.labelKey(), w.start(), w.end(), Baseline.STEP)
                .stream().filter(s -> pod.isEmpty() || pod.get().equals(s.label())).toList();
        if (series.isEmpty()) {
            return "no series for " + chart.path() + pod.map(p -> " on " + p).orElse("");
        }
        return series.stream().map(s -> s.label() + " (" + chart.unit() + "):" + downsample(s.points()).stream()
                .map(Baseline::num).collect(Collectors.joining(","))).collect(Collectors.joining("\n"));
    }

    private String events(JsonNode args, RunWindow w) {
        String kind = required(args, "kind");
        if (!KINDS.contains(kind)) {
            throw new BadArguments("kind must be one of " + KINDS);
        }
        List<String> lines = new ArrayList<>();
        for (Event e : objects.events(kind, required(args, "name"))) {
            String at = e.getLastTimestamp();
            if (at != null && inWindow(at, w) && lines.size() < EVENTS) {
                lines.add(at.substring(11, 19) + " " + e.getType() + " " + e.getReason()
                        + (e.getCount() != null && e.getCount() > 1 ? " ×" + e.getCount() : "") + ": " + e.getMessage());
            }
        }
        return lines.isEmpty() ? "no events in the run window" : String.join("\n", lines);
    }

    private String podStatus(JsonNode args) {
        Pod pod = ourPod(required(args, "pod"));
        List<ContainerStatus> cs = pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null
                ? List.of() : pod.getStatus().getContainerStatuses();
        boolean ready = !cs.isEmpty() && cs.stream().allMatch(c -> Boolean.TRUE.equals(c.getReady()));
        int restarts = cs.stream().mapToInt(ContainerStatus::getRestartCount).sum();
        String owner = pod.getMetadata().getOwnerReferences() == null ? "none" : pod.getMetadata().getOwnerReferences()
                .stream().map(OwnerReference::getName).findFirst().orElse("none");
        String node = pod.getSpec() == null || pod.getSpec().getNodeName() == null ? "unscheduled" : pod.getSpec().getNodeName();
        return (pod.getStatus() == null ? "Unknown" : pod.getStatus().getPhase()) + ", " + (ready ? "ready" : "not ready")
                + ", restarts " + restarts + ", node " + node + ", owner " + owner;
    }

    private String endpoints(JsonNode args) {
        String service = required(args, "service");
        List<String> ready = new ArrayList<>();
        for (EndpointSlice slice : objects.endpointSlices(service)) {
            slice.getEndpoints().forEach(e -> {
                if (e.getConditions() != null && Boolean.TRUE.equals(e.getConditions().getReady())
                        && e.getTargetRef() != null) {
                    ready.add(e.getTargetRef().getName());
                }
            });
        }
        return ready.isEmpty() ? "no ready endpoints" : ready.size() + " ready: " + String.join(", ", ready);
    }

    private Pod ourPod(String name) {
        Pod pod = objects.pod(name).orElseThrow(() -> new BadArguments("no pod named " + name));
        if (!Scope.ours(pod)) {
            throw new Refused(name);
        }
        return pod;
    }

    static List<Double> downsample(List<double[]> points) {
        List<Double> values = points.stream().map(p -> p[1]).filter(v -> !v.isNaN()).toList();
        if (values.size() <= POINTS) {
            return values;
        }
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < POINTS; i++) {
            int from = i * values.size() / POINTS;
            int to = (i + 1) * values.size() / POINTS;
            out.add(values.subList(from, to).stream().mapToDouble(Double::doubleValue).max().orElse(0));
        }
        return out;
    }

    private static boolean inWindow(String at, RunWindow w) {
        try {
            Instant t = Instant.parse(at);
            return !t.isBefore(w.start()) && !t.isAfter(w.end());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String required(JsonNode args, String key) {
        return optional(args, key).orElseThrow(() -> new BadArguments("'" + key + "' is required"));
    }

    private static Optional<String> optional(JsonNode args, String key) {
        JsonNode v = args == null ? null : args.get(key);
        return v == null || v.isNull() || v.asString().isBlank() ? Optional.empty() : Optional.of(v.asString());
    }

    private static String describe(String tool, JsonNode args) {
        return tool + "(" + (args == null ? "" : args.toString()) + ")";
    }

    static class BadArguments extends RuntimeException {
        BadArguments(String message) {
            super(message);
        }
    }

    static class Refused extends BadArguments {
        Refused(String pod) {
            super(pod);
        }
    }
}
