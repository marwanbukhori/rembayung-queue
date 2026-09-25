package dev.marwan.console.agent;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import dev.marwan.console.cluster.KubernetesAccess;
import dev.marwan.console.metrics.ChartName;
import dev.marwan.console.metrics.RangeQuery;
import dev.marwan.console.metrics.Series;
import dev.marwan.console.objects.LogLine;
import dev.marwan.console.objects.LogLines;
import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.state.DemoState;

/**
 * The facts every run gets before the model sees anything.
 *
 * Fixed and gathered the same way every time, so a report has something solid
 * to stand on even if the model asks for nothing. Each source is read on its
 * own: one that fails adds a fact saying so, and the rest still arrive. A
 * report that says "Prometheus was unavailable" is useful; an analysis that
 * threw is not.
 *
 * Nothing here is copied from a log line verbatim - only counts - so no phone
 * number can reach a fact from this class.
 */
public class Baseline {

    static final String POOL_TIMEOUT = "Connection is not available";
    static final Duration STEP = Duration.ofSeconds(15);
    static final List<String> WATCHED = List.of("booking-service", "queue-gate");
    static final String FIVE_XX = "max by (job) (increase(http_server_requests_seconds_count"
            + "{job=~\"queue-gate|booking-service\",status=~\"5..\",uri!~\"/actuator.*\"}[1m]))";

    private final ObjectSource objects;
    private final RangeQuery prometheus;
    private final Function<String, DemoState> state;

    public Baseline(ObjectSource objects, RangeQuery prometheus, Function<String, DemoState> state) {
        this.objects = objects;
        this.prometheus = prometheus;
        this.state = state;
    }

    public Facts gather(RunWindow w) {
        Facts facts = new Facts();
        k6(w, facts);
        oversold(w, facts);
        prometheus(w, facts);
        warnings(w, facts);
        restarts(facts);
        poolTimeouts(w, facts);
        return facts;
    }

    private void k6(RunWindow w, Facts facts) {
        Optional<K6Summary> summary;
        String why = "no K6_SUMMARY line in the load pod's log";
        try {
            Optional<Pod> pod = objects.pods("rembayung-load").stream()
                    .filter(p -> w.job().equals(p.getMetadata().getLabels().get("job-name")))
                    .min(java.util.Comparator.comparing(p -> p.getStatus() != null
                            && "Succeeded".equals(p.getStatus().getPhase()) ? 0 : 1));
            summary = pod.flatMap(p -> K6Summary.parse(objects.podLog(p.getMetadata().getName(), 50)));
            if (pod.isEmpty()) {
                why = "the load pod is gone";
            }
        } catch (RuntimeException e) {
            summary = Optional.empty();
            why = KubernetesAccess.summarise(e);
        }
        if (summary.isEmpty()) {
            facts.add("k6", "k6 summary", "unavailable: " + why);
            return;
        }
        K6Summary s = summary.get();
        facts.add("k6", "Customers arriving at once", String.valueOf(s.vus()));
        facts.add("k6", "Bookings: clean, rejected, not clean",
                s.booked() + " booked, " + s.rejected() + " rejected, " + s.notClean() + " not clean");
        facts.add("k6", "Request latency p50 / p95 / max",
                Math.round(s.p50()) + " / " + Math.round(s.p95()) + " / " + Math.round(s.max()) + " ms");
        facts.add("k6", "Run duration", Math.round(s.durationMs() / 1000.0) + " s");
    }

    private void oversold(RunWindow w, Facts facts) {
        try {
            DemoState s = state.apply(w.dropId());
            facts.add("invariant", "Seats oversold", s.available() ? String.valueOf(s.oversold())
                    : "unavailable: " + s.detail());
        } catch (RuntimeException e) {
            facts.add("invariant", "Seats oversold", "unavailable: " + e.getMessage());
        }
    }

    private void prometheus(RunWindow w, Facts facts) {
        try {
            for (Series s : range(ChartName.POOL.promql(), ChartName.POOL.labelKey(), w)) {
                facts.add("Prometheus", "Peak DB pool in use, " + s.label(), num(max(s)));
            }
            for (Series s : range(ChartName.LATENCY.promql(), ChartName.LATENCY.labelKey(), w)) {
                facts.add("Prometheus", "Peak p95 latency, " + s.label(), Math.round(max(s) * 1000) + " ms");
            }
            for (Series s : range(FIVE_XX, "job", w)) {
                facts.add("Prometheus", "Most 5xx responses in one minute, " + s.label(), num(max(s)));
            }
            for (Series s : range(ChartName.REPLICAS.promql(), ChartName.REPLICAS.labelKey(), w)) {
                facts.add("Prometheus", "Peak replicas, " + s.label(), num(max(s)) + " (from " + num(first(s)) + ")");
            }
        } catch (Exception e) {
            facts.add("Prometheus", "Prometheus", "unavailable: " + e.getMessage());
        }
    }

    private List<Series> range(String promql, String label, RunWindow w) throws Exception {
        return prometheus.range(promql, label, w.start(), w.end(), STEP);
    }

    private void warnings(RunWindow w, Facts facts) {
        List<String> seen = new ArrayList<>();
        try {
            for (String app : WATCHED) {
                collect("Deployment", app, w, seen);
                for (Pod pod : objects.pods(app)) {
                    collect("Pod", pod.getMetadata().getName(), w, seen);
                }
            }
        } catch (RuntimeException e) {
            facts.add("Kubernetes", "Warning events in the window", "unavailable: " + KubernetesAccess.summarise(e));
            return;
        }
        facts.add("Kubernetes", "Warning events in the window", seen.isEmpty() ? "none" : String.join("; ", seen));
    }

    private void collect(String kind, String name, RunWindow w, List<String> seen) {
        for (Event e : objects.events(kind, name)) {
            Instant at = at(e);
            if ("Warning".equals(e.getType()) && at != null && !at.isBefore(w.start()) && !at.isAfter(w.end())) {
                int count = e.getCount() == null ? 1 : e.getCount();
                seen.add(e.getReason() + (count > 1 ? " ×" + count : "") + " on " + kind + "/" + name);
            }
        }
    }

    private void restarts(Facts facts) {
        List<String> restarted = new ArrayList<>();
        try {
            for (String app : WATCHED) {
                for (Pod pod : objects.pods(app)) {
                    int n = pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null ? 0
                            : pod.getStatus().getContainerStatuses().stream()
                                    .mapToInt(ContainerStatus::getRestartCount).sum();
                    if (n > 0) {
                        restarted.add(pod.getMetadata().getName() + ": " + n);
                    }
                }
            }
        } catch (RuntimeException e) {
            facts.add("Kubernetes", "Pod restarts", "unavailable: " + KubernetesAccess.summarise(e));
            return;
        }
        facts.add("Kubernetes", "Pod restarts", restarted.isEmpty() ? "none" : String.join(", ", restarted));
    }

    private void poolTimeouts(RunWindow w, Facts facts) {
        List<Pod> pods;
        try {
            pods = objects.pods("booking-service");
        } catch (RuntimeException e) {
            return;
        }
        for (Pod pod : pods) {
            String name = pod.getMetadata().getName();
            try {
                long n = LogLines.parse(objects.podLog(name, 500)).stream()
                        .filter(l -> inWindow(l, w) && l.message() != null && l.message().contains(POOL_TIMEOUT))
                        .count();
                facts.add("logs", "Pool timeouts in the window, " + name, String.valueOf(n));
            } catch (RuntimeException e) {
                facts.add("logs", "Pool timeouts in the window, " + name,
                        "unavailable: " + KubernetesAccess.summarise(e));
            }
        }
    }

    static boolean inWindow(LogLine line, RunWindow w) {
        try {
            Instant at = Instant.parse(line.at());
            return !at.isBefore(w.start()) && !at.isAfter(w.end());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Instant at(Event e) {
        String t = e.getLastTimestamp() != null ? e.getLastTimestamp()
                : e.getEventTime() != null ? e.getEventTime().getTime() : null;
        try {
            return t == null ? null : Instant.parse(t);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static double max(Series s) {
        return s.points().stream().mapToDouble(p -> p[1]).filter(v -> !Double.isNaN(v)).max().orElse(0);
    }

    private static double first(Series s) {
        return s.points().stream().mapToDouble(p -> p[1]).filter(v -> !Double.isNaN(v)).findFirst().orElse(0);
    }

    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }
}
