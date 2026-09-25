package dev.marwan.console.metrics;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class MetricsServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC);
    private final PodReadings pods = mock(PodReadings.class);
    private final AtomicInteger promCalls = new AtomicInteger();

    @Test
    void historyAndNowArriveTogether() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> {
            promCalls.incrementAndGet();
            return List.of(new Series("booking-a", List.of(new double[]{1, 3})));
        }, pods, clock);
        given(pods.now(ChartName.POOL)).willReturn(List.of(new Reading("booking-a", 4)));

        Chart chart = metrics.chart(ChartName.POOL, 15);

        assertThat(chart.series()).hasSize(1);
        assertThat(chart.now()).extracting(Reading::value).containsExactly(4.0);
        assertThat(chart.history()).isNull();
        assertThat(chart.live()).isNull();
    }

    @Test
    void prometheusDownKeepsTheLiveReading() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> {
            throw new IllegalStateException("Prometheus answered HTTP 503");
        }, pods, clock);
        given(pods.now(ChartName.POOL)).willReturn(List.of(new Reading("booking-a", 4)));

        Chart chart = metrics.chart(ChartName.POOL, 15);

        assertThat(chart.series()).isEmpty();
        assertThat(chart.history()).contains("503");
        assertThat(chart.now()).hasSize(1);
    }

    @Test
    void podsUnreadableKeepTheHistory() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) ->
                List.of(new Series("queue-gate", List.of(new double[]{1, 2}))), pods, clock);
        given(pods.now(any())).willThrow(new IllegalStateException("API server refused"));

        Chart chart = metrics.chart(ChartName.REPLICAS, 15);

        assertThat(chart.series()).hasSize(1);
        assertThat(chart.live()).contains("API server refused");
    }

    /** Review focus 1. */
    @Test
    void noLatencyYetSaysWhy() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> List.of(), pods, clock);

        Chart chart = metrics.chart(ChartName.LATENCY, 15);

        assertThat(chart.history()).isEqualTo("No latency data yet - p95 needs requests with histograms on.");
    }

    @Test
    void historyIsCachedAcrossViewers() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> {
            promCalls.incrementAndGet();
            return List.of();
        }, pods, clock);

        metrics.chart(ChartName.POOL, 15);
        metrics.chart(ChartName.POOL, 15);

        assertThat(promCalls.get()).isEqualTo(1);
    }

    @Test
    void theWindowIsClamped() {
        AtomicReference<Instant> start = new AtomicReference<>();
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> {
            start.set(s);
            return List.of();
        }, pods, clock);

        metrics.chart(ChartName.POOL, 100_000);

        assertThat(start.get()).isEqualTo(Instant.parse("2026-09-25T09:00:00Z"));
    }
}
