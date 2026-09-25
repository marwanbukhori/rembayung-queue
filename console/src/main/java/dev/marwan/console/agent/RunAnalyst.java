package dev.marwan.console.agent;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marwan.console.cluster.KubernetesAccess;
import dev.marwan.console.objects.ObjectSource;

/**
 * Finds finished load runs that have no report and analyses them, one at a time.
 *
 * It reconciles rather than reacting to an event: every tick asks "which
 * finished runs have no report?", so a console restart mid-analysis loses
 * nothing - the next tick finds the same run again. A run is left alone until
 * forty-five seconds after it ended, so the thirty seconds of tail the facts
 * cover have been scraped by Prometheus.
 *
 * One analysis at a time, off the request path, on a virtual thread; a tick
 * that finds one still going does nothing.
 */
public class RunAnalyst {

    private static final Logger log = LoggerFactory.getLogger(RunAnalyst.class);
    static final Duration TAIL = Duration.ofSeconds(30);
    static final Duration SETTLE = Duration.ofSeconds(45);

    private final ObjectSource objects;
    private final Analyst analyst;
    private final AnalysisStore store;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();
    /** Runs whose analysis threw, and how often: given up after three, until the console restarts. */
    private final java.util.Map<String, Integer> failures = new java.util.concurrent.ConcurrentHashMap<>();
    static final int MAX_TRIES = 3;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    public RunAnalyst(ObjectSource objects, Analyst analyst, AnalysisStore store, Clock clock) {
        this.objects = objects;
        this.analyst = analyst;
        this.store = store;
        this.clock = clock;
    }

    /** The scheduled entry point: start one reconcile unless one is already running. */
    public void tick() {
        if (busy.compareAndSet(false, true)) {
            worker.submit(() -> {
                try {
                    reconcileOnce();
                } finally {
                    busy.set(false);
                }
            });
        }
    }

    public enum Rerun { STARTED, BUSY, UNKNOWN }

    /**
     * Run the model again on a stored run's facts, in the background. Shares the
     * one-at-a-time guard with the reconciler, so a re-analysis never doubles
     * the load on the shared model.
     */
    public Rerun rerun(String id) {
        Optional<Analysis> stored = store.get(id);
        if (stored.isEmpty()) {
            return Rerun.UNKNOWN;
        }
        if (!busy.compareAndSet(false, true)) {
            return Rerun.BUSY;
        }
        Analysis a = stored.get();
        worker.submit(() -> {
            try {
                store.put(analyst.reanalyse(new RunWindow(a.job(), a.dropId(), a.start(), a.end()), a.facts()));
            } catch (RuntimeException e) {
                log.warn("re-analysis of {} failed: {}", id, rootCause(e));
            } finally {
                busy.set(false);
            }
        });
        return Rerun.STARTED;
    }

    void reconcileOnce() {
        try {
            Set<String> done = store.keys();
            Instant now = clock.instant();
            Optional<RunWindow> next = objects.jobs().stream()
                    .filter(j -> "rembayung-load".equals(label(j, "app")))
                    .map(this::window).filter(Objects::nonNull)
                    .filter(w -> !done.contains(w.key()))
                    .filter(w -> failures.getOrDefault(w.key(), 0) < MAX_TRIES)
                    .filter(w -> !now.isBefore(w.end().minus(TAIL).plus(SETTLE)))
                    .min(Comparator.comparing(RunWindow::start));
            if (next.isEmpty()) {
                return;
            }
            RunWindow w = next.get();
            Analysis analysis;
            try {
                analysis = analyst.analyse(w);
                store.put(analysis);
            } catch (RuntimeException e) {
                int tries = failures.merge(w.key(), 1, Integer::sum);
                log.warn("analysis of {} failed (try {} of {}): {}", w.key(), tries, MAX_TRIES, rootCause(e));
                return;
            }
            log.info("analysed {}: {} report in {} ms{}", w.job(), analysis.source(), analysis.millis(),
                    analysis.note() == null ? "" : " (" + analysis.note() + ")");
        } catch (RuntimeException e) {
            log.warn("run analysis skipped this tick: {}", KubernetesAccess.summarise(e));
        }
    }

    /** fabric8 wraps everything in "An error has occurred."; the innermost cause says what happened. */
    static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return KubernetesAccess.summarise(e) + (t == e ? "" : " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
    }

    /** Stops the worker with the application context. */
    public void close() {
        worker.shutdownNow();
    }

    /** Start to end plus the tail, or null while the run is still going. */
    private RunWindow window(Job job) {
        if (job.getStatus() == null || job.getStatus().getStartTime() == null) {
            return null;
        }
        Instant end = null;
        if (job.getStatus().getCompletionTime() != null) {
            end = Instant.parse(job.getStatus().getCompletionTime());
        } else if (job.getStatus().getFailed() != null && job.getStatus().getFailed() > 0) {
            end = Optional.ofNullable(job.getStatus().getConditions()).orElse(java.util.List.of()).stream()
                    .filter(c -> "Failed".equals(c.getType()) && c.getLastTransitionTime() != null)
                    .map(JobCondition::getLastTransitionTime).map(Instant::parse).findFirst()
                    .orElse(clock.instant().minus(SETTLE));
        }
        if (end == null) {
            return null;
        }
        return new RunWindow(job.getMetadata().getName(), dropId(job),
                Instant.parse(job.getStatus().getStartTime()), end.plus(TAIL));
    }

    /** The drop as the run was given it; the label is a DNS-safe copy that may differ in case. */
    private static String dropId(Job job) {
        try {
            return job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                    .filter(e -> "DROP_ID".equals(e.getName())).map(EnvVar::getValue).findFirst()
                    .orElse(label(job, "rembayung.dev/drop"));
        } catch (RuntimeException e) {
            return label(job, "rembayung.dev/drop");
        }
    }

    private static String label(Job job, String key) {
        return job.getMetadata().getLabels() == null ? null : job.getMetadata().getLabels().get(key);
    }
}
