package dev.marwan.booking.config;

import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.service.SlotStateProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.function.ToDoubleFunction;

/**
 * Binds the domain's own numbers to Micrometer.
 *
 * rembayung_slot_oversold is the one that matters. It must read 0 forever, and
 * it is deliberately redundant with the database's ck_slots_seats CHECK
 * constraint that makes the condition impossible to persist. That redundancy is
 * the point: if the gauge ever moves, either the invariant broke or the metric
 * is lying, and both deserve waking someone up.
 */
@Component
public class BookingMetrics implements MeterBinder {

    private final SlotStateProvider provider;

    public BookingMetrics(SlotStateProvider provider) {
        this.provider = provider;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (Long slotId : provider.trackedSlotIds()) {
            register(registry, "rembayung_slot_capacity", slotId, SlotState::capacity);
            register(registry, "rembayung_slot_seats_taken", slotId, SlotState::seatsTaken);
            register(registry, "rembayung_slot_remaining", slotId, SlotState::remaining);
            register(registry, "rembayung_slot_oversold", slotId, SlotState::oversold);
        }
    }

    private void register(MeterRegistry registry, String name, Long slotId,
                          ToDoubleFunction<SlotState> value) {
        Gauge.builder(name, slotId, id -> provider.stateFor(id)
                        .map(value::applyAsDouble)
                        // A slot that disappeared has no value, which is not the
                        // same as zero. Reporting 0 seats taken for a deleted
                        // slot would look like an empty restaurant.
                        .orElse(Double.NaN))
                .tag("slot", String.valueOf(slotId))
                .register(registry);
    }
}
