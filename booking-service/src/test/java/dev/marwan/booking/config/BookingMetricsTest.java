package dev.marwan.booking.config;

import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.service.SlotStateProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingMetricsTest {

    @Test
    void publishesSlotGaugesTaggedBySlot() {
        SlotStateProvider provider = mock(SlotStateProvider.class);
        when(provider.trackedSlotIds()).thenReturn(List.of(1L));
        when(provider.stateFor(1L)).thenReturn(Optional.of(SlotState.of(1L, 250, 202)));

        MeterRegistry registry = new SimpleMeterRegistry();
        new BookingMetrics(provider).bindTo(registry);

        assertThat(registry.get("rembayung_slot_capacity").tag("slot", "1").gauge().value())
                .isEqualTo(250.0);
        assertThat(registry.get("rembayung_slot_seats_taken").tag("slot", "1").gauge().value())
                .isEqualTo(202.0);
        assertThat(registry.get("rembayung_slot_remaining").tag("slot", "1").gauge().value())
                .isEqualTo(48.0);
        assertThat(registry.get("rembayung_slot_oversold").tag("slot", "1").gauge().value())
                .isZero();
    }

    // A slot that vanishes between registration and scrape must not throw and
    // must not report a stale value. NaN is Micrometer's "no value right now".
    @Test
    void reportsNaNRatherThanThrowingWhenASlotDisappears() {
        SlotStateProvider provider = mock(SlotStateProvider.class);
        when(provider.trackedSlotIds()).thenReturn(List.of(1L));
        when(provider.stateFor(1L)).thenReturn(Optional.empty());

        MeterRegistry registry = new SimpleMeterRegistry();
        new BookingMetrics(provider).bindTo(registry);

        assertThat(registry.get("rembayung_slot_seats_taken").tag("slot", "1").gauge().value())
                .isNaN();
    }
}
