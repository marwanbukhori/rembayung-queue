package dev.marwan.console.slo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marwan.console.metrics.RangeQuery;
import dev.marwan.console.metrics.Series;

class SloServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    static RangeQuery prom(double total, double errors, double p95) {
        long t = CLOCK.instant().getEpochSecond();
        return (q, l, s, e, st) -> {
            double v = q.contains("_bucket") ? p95 : q.contains("5..") ? errors : total;
            if (Double.isNaN(v)) {
                return List.of();
            }
            return List.of(new Series("booking-service", List.of(new double[] {t - 15, v}, new double[] {t, v})));
        };
    }

    @Test
    void healthy() {
        SloReading r = new SloService(prom(10, 0, 0.8), CLOCK).now();
        assertThat(r.available()).isTrue();
        assertThat(r.hasTraffic()).isTrue();
        assertThat(r.successRatio()).isEqualTo(1.0);
        assertThat(r.breached()).isFalse();
    }

    @Test
    void aSuccessBreach() {
        SloReading r = new SloService(prom(10, 0.5, 0.8), CLOCK).now();
        assertThat(r.successRatio()).isEqualTo(0.95);
        assertThat(r.successBreached()).isTrue();
        assertThat(r.breached()).isTrue();
    }

    @Test
    void aLatencyBreach() {
        SloReading r = new SloService(prom(10, 0, 3.4), CLOCK).now();
        assertThat(r.latencyBreached()).isTrue();
        assertThat(r.breached()).isTrue();
    }

    @Test
    void noTrafficIsNotABreach() {
        SloReading r = new SloService(prom(0, 0, Double.NaN), CLOCK).now();
        assertThat(r.hasTraffic()).isFalse();
        assertThat(r.breached()).isFalse();
    }

    @Test
    void prometheusDownIsUnavailableNotABreach() {
        RangeQuery down = (q, l, s, e, st) -> { throw new IllegalStateException("Prometheus answered HTTP 503"); };
        SloReading r = new SloService(down, CLOCK).now();
        assertThat(r.available()).isFalse();
        assertThat(r.breached()).isFalse();
        assertThat(r.detail()).contains("503");
    }
}
