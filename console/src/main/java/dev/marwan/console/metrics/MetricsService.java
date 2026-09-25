package dev.marwan.console.metrics;

import dev.marwan.console.cluster.KubernetesAccess;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A chart, from two sources that fail separately.
 *
 * Prometheus's history is reused for ten seconds (it scrapes every fifteen);
 * the pods' live reading for two. However many people watch, the cluster sees
 * one query per chart per ten seconds and one read per pod per two.
 *
 * Reads never run under a lock that viewers wait on. Each key has at most one
 * refresh in flight, on a virtual thread; while it runs, callers get the last
 * value. Only the very first read of a key is waited for. The window snaps to
 * four sizes, so a caller cannot fan out queries by cycling through minutes.
 */
@Component
public class MetricsService {

    static final Duration HISTORY_TTL = Duration.ofSeconds(10);
    static final Duration LIVE_TTL = Duration.ofSeconds(2);
    static final Duration STEP = Duration.ofSeconds(15);
    static final int[] WINDOWS = {5, 15, 30, 60};
    static final long FIRST_READ_WAIT_SECONDS = 6;
    static final String NO_LATENCY = "No latency data yet - p95 needs requests with histograms on.";

    private final RangeQuery prometheus;
    private final PodReadings pods;
    private final Clock clock;
    private final Map<String, Held<List<Series>>> history = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Held<List<Series>>>> historyInFlight = new ConcurrentHashMap<>();
    private final Map<ChartName, Held<List<Reading>>> live = new ConcurrentHashMap<>();
    private final Map<ChartName, CompletableFuture<Held<List<Reading>>>> liveInFlight = new ConcurrentHashMap<>();
    private final ExecutorService refreshers = Executors.newVirtualThreadPerTaskExecutor();

    private record Held<T>(T value, String failure, Instant at) { }

    public MetricsService(RangeQuery prometheus, PodReadings pods, Clock clock) {
        this.prometheus = prometheus;
        this.pods = pods;
        this.clock = clock;
    }

    public Chart chart(ChartName chart, int minutes) {
        int window = snap(minutes);
        Held<List<Series>> h = get(history, historyInFlight, chart.path() + "/" + window, HISTORY_TTL,
                () -> readHistory(chart, window));
        Held<List<Reading>> l = get(live, liveInFlight, chart, LIVE_TTL, () -> readLive(chart));
        String historyNote = h.failure() != null ? h.failure()
                : chart == ChartName.LATENCY && h.value().isEmpty() ? NO_LATENCY : null;
        return new Chart(chart.path(), chart.unit(), historyNote, h.value(), l.failure(), l.value());
    }

    private Held<List<Series>> readHistory(ChartName chart, int minutes) {
        Instant end = clock.instant();
        try {
            return new Held<>(prometheus.range(chart.promql(), chart.labelKey(),
                    end.minus(Duration.ofMinutes(minutes)), end, STEP), null, clock.instant());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Held<>(List.of(), "History unavailable: interrupted", clock.instant());
        } catch (Exception e) {
            return new Held<>(List.of(), "History unavailable: " + KubernetesAccess.summarise(e), clock.instant());
        }
    }

    private Held<List<Reading>> readLive(ChartName chart) {
        try {
            return new Held<>(pods.now(chart), null, clock.instant());
        } catch (RuntimeException e) {
            return new Held<>(List.of(), "Live reading unavailable: " + KubernetesAccess.summarise(e),
                    clock.instant());
        }
    }

    /** The last value if fresh; otherwise one refresh, and the stale value while it runs. */
    private <K, T> Held<List<T>> get(Map<K, Held<List<T>>> held, Map<K, CompletableFuture<Held<List<T>>>> inFlight,
                                     K key, Duration ttl, Supplier<Held<List<T>>> read) {
        Held<List<T>> current = held.get(key);
        if (fresh(current, ttl)) {
            return current;
        }
        CompletableFuture<Held<List<T>>> mine = new CompletableFuture<>();
        CompletableFuture<Held<List<T>>> running = inFlight.putIfAbsent(key, mine);
        if (running == null) {
            running = mine;
            refreshers.submit(() -> {
                try {
                    Held<List<T>> value = read.get();
                    held.put(key, value);
                    mine.complete(value);
                } catch (Throwable e) {
                    mine.completeExceptionally(e);
                } finally {
                    inFlight.remove(key, mine);
                }
            });
        }
        if (current != null) {
            return current;
        }
        try {
            return running.get(FIRST_READ_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return new Held<>(List.of(), "Still reading - try again in a moment", clock.instant());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Held<>(List.of(), "Interrupted", clock.instant());
        } catch (Exception e) {
            return new Held<>(List.of(), KubernetesAccess.summarise(e), clock.instant());
        }
    }

    static int snap(int minutes) {
        int best = WINDOWS[0];
        for (int w : WINDOWS) {
            if (Math.abs(w - minutes) < Math.abs(best - minutes)) {
                best = w;
            }
        }
        return best;
    }

    private boolean fresh(Held<?> held, Duration ttl) {
        return held != null && held.at().plus(ttl).isAfter(clock.instant());
    }
}
