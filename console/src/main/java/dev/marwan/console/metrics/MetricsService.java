package dev.marwan.console.metrics;

import dev.marwan.console.cluster.KubernetesAccess;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A chart, from two sources that fail separately.
 *
 * Prometheus's history is reused for ten seconds (it scrapes every fifteen);
 * the pods' live reading for two. However many people watch, the cluster sees
 * one query per chart per ten seconds and one read per pod per two. The window
 * is clamped to 5-60 minutes, so the history map holds at most 4 x 56 entries.
 */
@Component
public class MetricsService {

    static final Duration HISTORY_TTL = Duration.ofSeconds(10);
    static final Duration LIVE_TTL = Duration.ofSeconds(2);
    static final Duration STEP = Duration.ofSeconds(15);
    static final int MIN_MINUTES = 5;
    static final int MAX_MINUTES = 60;
    static final String NO_LATENCY = "No latency data yet - p95 needs requests with histograms on.";

    private final RangeQuery prometheus;
    private final PodReadings pods;
    private final Clock clock;
    private final Map<String, Held<List<Series>>> history = new ConcurrentHashMap<>();
    private final Map<ChartName, Held<List<Reading>>> live = new ConcurrentHashMap<>();

    private record Held<T>(T value, String failure, Instant at) { }

    public MetricsService(RangeQuery prometheus, PodReadings pods, Clock clock) {
        this.prometheus = prometheus;
        this.pods = pods;
        this.clock = clock;
    }

    public Chart chart(ChartName chart, int minutes) {
        int window = Math.clamp(minutes, MIN_MINUTES, MAX_MINUTES);
        Held<List<Series>> h = history.compute(chart.path() + "/" + window, (k, held) ->
                fresh(held, HISTORY_TTL) ? held : readHistory(chart, window));
        Held<List<Reading>> l = live.compute(chart, (k, held) ->
                fresh(held, LIVE_TTL) ? held : readLive(chart));
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

    private boolean fresh(Held<?> held, Duration ttl) {
        return held != null && held.at().plus(ttl).isAfter(clock.instant());
    }
}
