package dev.marwan.console.slo;

import java.time.Instant;

/**
 * The two booking SLOs at one moment.
 *
 * A reading with no traffic is never a breach - an idle system has no success
 * ratio worth alarming on - and a reading Prometheus could not give is
 * "unavailable", never a breach either: the incident watcher must not open an
 * incident because monitoring went away.
 */
public record SloReading(Instant at, boolean available, String detail, boolean hasTraffic,
                         Double successRatio, Double p95Seconds) {

    public static final double SUCCESS_TARGET = 0.99;
    public static final double LATENCY_TARGET_SECONDS = 2.0;

    public boolean successBreached() {
        return available && hasTraffic && successRatio != null && successRatio < SUCCESS_TARGET;
    }

    public boolean latencyBreached() {
        return available && hasTraffic && p95Seconds != null && p95Seconds > LATENCY_TARGET_SECONDS;
    }

    public boolean breached() {
        return successBreached() || latencyBreached();
    }
}
