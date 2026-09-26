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
    private final int poolSize;

    public Baseline(ObjectSource objects, RangeQuery prometheus, int poolSize, Function<String, DemoState> state) {
        this.objects = objects;
        this.prometheus = prometheus;
        this.poolSize = poolSize;
        this.state = state;
    }

    public Facts gather(RunWindow w) {
        Facts facts = new Facts();
        Optional<K6Summary> k6 = k6(w, facts);
        // Only a wave that actually ran is compared: a run cut off after wave 1 has no wave 2 to stand beside it.
        boolean waveTwoRan = k6.map(s -> s.perWave().size() == 2).orElse(false);
        oversold(w, facts, k6.map(K6Summary::patienceSeconds).orElse(null), waveTwoRan);
        // Configuration, not a measurement - but a report comparing a peak with the pool's size
        // needs the size as a fact to cite, or the validator rightly refuses the number.
        facts.add("config", "DB pool size per booking-service pod", String.valueOf(poolSize));
        prometheus(w, facts, waveTwoRan);
        warnings(w, facts);
        restarts(facts);
        poolTimeouts(w, facts);
        return facts;
    }

    private Optional<K6Summary> k6(RunWindow w, Facts facts) {
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
            return summary;
        }
        K6Summary s = summary.get();
        facts.add("k6", "Customers arriving at once", String.valueOf(s.vus()));
        facts.add("k6", "Bookings: clean, rejected, not clean",
                s.booked() + " booked, " + s.rejected() + " rejected, " + s.notClean() + " not clean");
        facts.add("k6", "Request latency p50 / p95 / max",
                Math.round(s.p50()) + " / " + Math.round(s.p95()) + " / " + Math.round(s.max()) + " ms");
        facts.add("k6", "Run duration", Math.round(s.durationMs() / 1000.0) + " s");
        if (!s.hasOutcomes()) {
            facts.add("k6", "Customer outcomes", "unavailable: this run's k6 script predates outcome counting");
            return summary;
        }
        // The funnel, one fact a step, then one per way out of it - so each drop-off can be cited.
        facts.add("k6", "Party size", String.valueOf(s.partySize()));
        facts.add("k6", "Queue patience", s.patienceSeconds() + " s");
        facts.add("k6", "Arrived", String.valueOf(s.vus()));
        facts.add("k6", "Joined the queue", String.valueOf(s.joined()));
        facts.add("k6", "Admitted", String.valueOf(s.admitted()));
        facts.add("k6", "Booked", String.valueOf(s.booked()));
        facts.add("k6", "Seats taken by this run", String.valueOf(s.booked() * (s.partySize() == null ? 0 : s.partySize())));
        outcome(facts, "Gave up waiting (403)", s.gaveUp());
        outcome(facts, "Sold out at the queue (409)", s.soldOutAtJoin());
        outcome(facts, "Sold out at booking (409)", s.soldOut());
        outcome(facts, "Admitted but refused (403)", s.refusedAfterAdmission());
        outcome(facts, "Overloaded (503)", s.overloaded());
        if (s.faultsAtJoin() != null && s.faultsAtBooking() != null) {
            outcome(facts, "Faults at the queue", s.faultsAtJoin());
            outcome(facts, "Faults at booking", s.faultsAtBooking());
        } else {
            outcome(facts, "Other faults", s.faults());
        }
        // A customer cut off by the run's time limit, or given a reply it could not read, reached no outcome.
        int finished = s.booked() + nz(s.soldOutAtJoin()) + nz(s.gaveUp()) + nz(s.refusedAfterAdmission())
                + nz(s.soldOut()) + nz(s.overloaded()) + nz(s.faults());
        outcome(facts, "Did not finish", s.vus() - finished);
        facts.add("k6", "Queue wait p50 / p95 / max",
                s.queueWaitP50() + " / " + s.queueWaitP95() + " / " + s.queueWaitMax() + " s");
        if (s.waves() == 2) {
            // Each wave on its own, so the report can set wave 1 beside wave 2. A run cut
            // off before wave 2 lists only the wave it had.
            for (K6Summary.Wave wave : s.perWave()) {
                wave(facts, wave);
            }
        }
        return summary;
    }

    private static void wave(Facts facts, K6Summary.Wave w) {
        String p = "Wave " + w.wave() + " · ";
        facts.add("k6", p + "Arrived", String.valueOf(w.vus()));
        facts.add("k6", p + "Joined the queue", String.valueOf(nz(w.joined())));
        facts.add("k6", p + "Admitted", String.valueOf(nz(w.admitted())));
        facts.add("k6", p + "Booked", String.valueOf(w.booked()));
        outcome(facts, p + "Gave up waiting (403)", w.gaveUp());
        outcome(facts, p + "Sold out at the queue (409)", w.soldOutAtJoin());
        outcome(facts, p + "Sold out at booking (409)", w.soldOut());
        outcome(facts, p + "Admitted but refused (403)", w.refusedAfterAdmission());
        outcome(facts, p + "Overloaded (503)", w.overloaded());
        outcome(facts, p + "Faults at the queue", w.faultsAtJoin());
        outcome(facts, p + "Faults at booking", w.faultsAtBooking());
        if (w.p95() != null && w.max() != null) {
            facts.add("k6", p + "Latency p95 / max", w.p95() + " / " + w.max() + " ms");
        }
    }

    private static int nz(Integer n) {
        return n == null ? 0 : n;
    }

    private static void outcome(Facts facts, String label, Integer n) {
        if (n != null && n > 0) {
            facts.add("k6", label, String.valueOf(n));
        }
    }

    private void oversold(RunWindow w, Facts facts, Integer patienceSeconds, boolean waveTwoRan) {
        try {
            DemoState s = state.apply(w.dropId());
            DemoState second = waveTwoRan && w.wave2DropId() != null ? state.apply(w.wave2DropId()) : null;
            if (second != null) {
                // Each wave sold its own sitting; the invariant has to hold in both.
                facts.add("invariant", "Wave 1 · Seats oversold", s.available() ? String.valueOf(s.oversold())
                        : "unavailable: " + s.detail());
                facts.add("invariant", "Wave 2 · Seats oversold", second.available() ? String.valueOf(second.oversold())
                        : "unavailable: " + second.detail());
                if (second.available()) {
                    facts.add("booking-service", "Wave 2 · Seats taken / capacity",
                            second.seatsTaken() + " / " + second.capacity());
                }
            }
            boolean bothReadable = s.available() && (second == null || second.available());
            facts.add("invariant", "Seats oversold", bothReadable
                    ? String.valueOf(s.oversold() + (second == null ? 0 : second.oversold()))
                    : "unavailable: " + (s.available() ? second.detail() : s.detail()));
            if (s.available()) {
                facts.add("queue-gate", "Admit rate", s.admitRate() == null
                        ? "unavailable: the gate did not report it" : s.admitRate() + " per second");
                if (s.admitRate() != null && patienceSeconds != null) {
                    // Computed here, as a fact, so a report can cite it rather than do the sum itself.
                    facts.add("queue-gate", "Admissions possible within patience",
                            String.valueOf((long) s.admitRate() * patienceSeconds));
                }
                facts.add("queue-gate", "Tickets issued", String.valueOf(s.ticketsIssued()));
                facts.add("queue-gate", "Admitted by the gate", String.valueOf(s.admitted()));
                facts.add("queue-gate", "Still waiting", String.valueOf(s.waiting()));
                facts.add("booking-service", "Seats taken / capacity", s.seatsTaken() + " / " + s.capacity());
            }
        } catch (RuntimeException e) {
            facts.add("invariant", "Seats oversold", "unavailable: " + e.getMessage());
        }
    }

    private void prometheus(RunWindow w, Facts facts, boolean waveTwoRan) {
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
                timeline(s, w).ifPresent(t -> facts.add("Prometheus", "Scaling, " + s.label(), t));
                if (w.waves() == 2 && waveTwoRan) {
                    for (int n = 1; n <= 2; n++) {
                        facts.add("Prometheus", "Wave " + n + " · Ready pods at start, " + s.label(),
                                num(valueAt(s, w.waveStart(n))));
                    }
                }
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
        List<String> held = new ArrayList<>();
        try {
            for (String app : WATCHED) {
                collect("Deployment", app, w, seen, held);
                for (var rs : objects.replicaSets(app)) {
                    collect("ReplicaSet", rs.getMetadata().getName(), w, seen, held);
                }
                for (Pod pod : objects.pods(app)) {
                    collect("Pod", pod.getMetadata().getName(), w, seen, held);
                }
            }
        } catch (RuntimeException e) {
            facts.add("Kubernetes", "Warning events in the window", "unavailable: " + KubernetesAccess.summarise(e));
            return;
        }
        facts.add("Kubernetes", "Warning events in the window", seen.isEmpty() ? "none" : String.join("; ", seen));
        facts.add("Kubernetes", "Pods waiting for CPU during the run", held.isEmpty() ? "none"
                : String.join("; ", held.stream().limit(3).toList()));
    }

    private void collect(String kind, String name, RunWindow w, List<String> seen, List<String> held) {
        for (Event e : objects.events(kind, name)) {
            Instant at = at(e);
            if ("Warning".equals(e.getType()) && at != null && !at.isBefore(w.start()) && !at.isAfter(w.end())) {
                int count = e.getCount() == null ? 1 : e.getCount();
                seen.add(e.getReason() + (count > 1 ? " ×" + count : "") + " on " + kind + "/" + name);
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (message.contains("exceeded quota") || message.contains("Insufficient cpu")) {
                    String line = kind + "/" + name + ": " + message;
                    String cut = line.length() > 200 ? line.substring(0, 200) + "…" : line;
                    if (!held.contains(cut)) {
                        held.add(cut);
                    }
                }
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

    /** Each change in an autoscaler's replicas during the run, as "2 → 6 at +1m15s"; empty if it never changed. */
    static Optional<String> timeline(Series s, RunWindow w) {
        List<String> changes = new ArrayList<>();
        Long previous = null;
        for (double[] p : s.points()) {
            if (Double.isNaN(p[1]) || p[0] < w.start().getEpochSecond()) {
                continue;
            }
            long value = Math.round(p[1]);
            if (previous != null && value != previous) {
                long offset = (long) p[0] - w.start().getEpochSecond();
                changes.add(previous + " → " + value + " at +" + offset / 60 + "m" + offset % 60 + "s");
            }
            previous = value;
        }
        return changes.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", changes));
    }

    /** The series' last value at or before a moment, or its first if it starts later. */
    static double valueAt(Series s, Instant at) {
        double value = Double.NaN;
        for (double[] p : s.points()) {
            if (Double.isNaN(p[1])) {
                continue;
            }
            if (p[0] <= at.getEpochSecond() || Double.isNaN(value)) {
                value = p[1];
            }
            if (p[0] > at.getEpochSecond()) {
                break;
            }
        }
        return Double.isNaN(value) ? 0 : value;
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
