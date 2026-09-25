package dev.marwan.booking.config;

import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.service.SlotStateProvider;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Binds the domain's own numbers to Micrometer.
 *
 * rembayung_slot_oversold is the one that matters. It must read 0 forever, and
 * it is deliberately redundant with the database's ck_slots_seats CHECK
 * constraint that makes the condition impossible to persist. That redundancy is
 * the point: if the gauge ever moves, either the invariant broke or the metric
 * is lying, and both deserve waking someone up.
 *
 * MultiGauge rather than a plain Gauge per slot, because the set of slots is not
 * fixed at startup. An earlier version enumerated slots once in bindTo(), which
 * meant a slot seeded afterwards — a documented, routine operation, see
 * loadtest/README.md — carried no gauges at all until the pod restarted. The
 * SlotOversold alert would then have silently failed to cover the very slot the
 * demo was filling, which is the same class of failure as an alert that never
 * fires because it matches nothing.
 */
@Component
public class BookingMetrics implements MeterBinder {

    /**
     * How old a gauge value may be. Scrape-time reads were current but cost a
     * query per slot per gauge on the booking pool; five seconds of staleness
     * is nothing next to a 15-second scrape interval, and oversold - the value
     * that matters - is impossible to persist anyway (ck_slots_seats).
     */
    static final Duration MAX_AGE = Duration.ofSeconds(5);

    private final SlotStateProvider provider;
    private final Supplier<Instant> now;
    private volatile Snapshot snapshot;

    private record Snapshot(Map<Long, SlotState> states, Instant at) { }

    private MultiGauge capacity;
    private MultiGauge seatsTaken;
    private MultiGauge remaining;
    private MultiGauge oversold;

    @Autowired
    public BookingMetrics(SlotStateProvider provider) {
        this(provider, Instant::now);
    }

    BookingMetrics(SlotStateProvider provider, Supplier<Instant> now) {
        this.provider = provider;
        this.now = now;
    }

    /** Every slot's state, from one query at most every MAX_AGE, however many gauges read it. */
    private Map<Long, SlotState> states() {
        Snapshot held = snapshot;
        Instant at = now.get();
        if (held != null && held.at().plus(MAX_AGE).isAfter(at)) {
            return held.states();
        }
        synchronized (this) {
            held = snapshot;
            if (held == null || !held.at().plus(MAX_AGE).isAfter(at)) {
                held = new Snapshot(provider.permanentStates(), at);
                snapshot = held;
            }
            return held.states();
        }
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        capacity = MultiGauge.builder("rembayung_slot_capacity").register(registry);
        seatsTaken = MultiGauge.builder("rembayung_slot_seats_taken").register(registry);
        remaining = MultiGauge.builder("rembayung_slot_remaining").register(registry);
        oversold = MultiGauge.builder("rembayung_slot_oversold").register(registry);
        refresh();
    }

    /**
     * Matches ExpirySweeper's cadence. A slot appearing mid-run is visible within
     * 30s rather than never.
     */
    @Scheduled(fixedDelayString = "PT30S")
    public void refresh() {
        if (capacity == null) {
            return; // not bound yet
        }
        List<Long> slotIds = List.copyOf(states().keySet());
        rebuild(capacity, slotIds, SlotState::capacity);
        rebuild(seatsTaken, slotIds, SlotState::seatsTaken);
        rebuild(remaining, slotIds, SlotState::remaining);
        rebuild(oversold, slotIds, SlotState::oversold);
    }

    private void rebuild(MultiGauge gauge, List<Long> slotIds,
                         ToDoubleFunction<SlotState> value) {
        gauge.register(
                slotIds.stream()
                        .map(id -> MultiGauge.Row.of(
                                Tags.of("slot", String.valueOf(id)),
                                id,
                                // Read from the snapshot at scrape time: at most
                                // MAX_AGE old, and one query for every slot.
                                slotId -> Optional.ofNullable(states().get((Long) slotId))
                                        .map(value::applyAsDouble)
                                        // A slot that disappeared has no value,
                                        // which is not the same as zero. Reporting
                                        // 0 seats taken for a deleted slot would
                                        // look like an empty restaurant.
                                        .orElse(Double.NaN)))
                        .toList(),
                true);
    }
}
