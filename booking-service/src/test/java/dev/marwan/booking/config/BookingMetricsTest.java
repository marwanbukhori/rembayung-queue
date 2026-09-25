package dev.marwan.booking.config;

import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.service.SlotStateProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingMetricsTest {

    @Test
    void publishesSlotGaugesTaggedBySlot() {
        SlotStateProvider provider = mock(SlotStateProvider.class);
        when(provider.permanentStates()).thenReturn(Map.of(1L, SlotState.of(1L, 250, 202)));

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
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
        when(provider.permanentStates()).thenReturn(Map.of(1L, SlotState.of(1L, 250, 10)));

        MeterRegistry registry = new SimpleMeterRegistry();
        new BookingMetrics(provider, now::get).bindTo(registry);
        when(provider.permanentStates()).thenReturn(Map.of());
        now.set(now.get().plusSeconds(6));

        assertThat(registry.get("rembayung_slot_seats_taken").tag("slot", "1").gauge().value())
                .isNaN();
    }

    // The reason MultiGauge is used at all. An earlier version enumerated slots
    // once in bindTo(), so a slot seeded after startup — routine, see
    // loadtest/README.md — carried no gauges until the pod restarted, and the
    // SlotOversold alert silently did not cover it.
    @Test
    void picksUpASlotThatAppearsAfterBinding() {
        SlotStateProvider provider = mock(SlotStateProvider.class);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
        when(provider.permanentStates()).thenReturn(Map.of(1L, SlotState.of(1L, 250, 10)));

        MeterRegistry registry = new SimpleMeterRegistry();
        BookingMetrics metrics = new BookingMetrics(provider, now::get);
        metrics.bindTo(registry);

        assertThat(registry.find("rembayung_slot_oversold").tag("slot", "2").gauge()).isNull();

        // A second slot is seeded.
        when(provider.permanentStates()).thenReturn(Map.of(
                1L, SlotState.of(1L, 250, 10), 2L, SlotState.of(2L, 100, 100)));
        now.set(now.get().plusSeconds(6));
        metrics.refresh();

        assertThat(registry.get("rembayung_slot_capacity").tag("slot", "2").gauge().value())
                .isEqualTo(100.0);
        assertThat(registry.get("rembayung_slot_oversold").tag("slot", "2").gauge().value())
                .isZero();
    }

    /**
     * The cost that made booking-service's scrape take 4.6s: four gauges per
     * slot, each a findById against Oracle in another region, on the booking
     * pool. A whole scrape now reads every slot with one query.
     */
    @Test
    void aScrapeOfEverySlotCostsOneQuery() {
        SlotStateProvider provider = mock(SlotStateProvider.class);
        when(provider.permanentStates()).thenReturn(Map.of(
                1L, SlotState.of(1L, 250, 10), 2L, SlotState.of(2L, 100, 5), 3L, SlotState.of(3L, 50, 50)));
        MeterRegistry registry = new SimpleMeterRegistry();
        new BookingMetrics(provider, () -> Instant.parse("2026-09-25T10:00:00Z")).bindTo(registry);
        clearInvocations(provider);

        for (String name : List.of("rembayung_slot_capacity", "rembayung_slot_seats_taken",
                "rembayung_slot_remaining", "rembayung_slot_oversold")) {
            for (String slot : List.of("1", "2", "3")) {
                registry.get(name).tag("slot", slot).gauge().value();
            }
        }

        verify(provider, atMost(1)).permanentStates();
        verify(provider, never()).stateFor(anyLong());
    }

    /** The trade for that: a value is at most five seconds old, not computed at the scrape. */
    @Test
    void valuesAreAtMostFiveSecondsOld() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
        SlotStateProvider provider = mock(SlotStateProvider.class);
        when(provider.permanentStates()).thenReturn(Map.of(1L, SlotState.of(1L, 250, 10)));
        MeterRegistry registry = new SimpleMeterRegistry();
        new BookingMetrics(provider, now::get).bindTo(registry);

        when(provider.permanentStates()).thenReturn(Map.of(1L, SlotState.of(1L, 250, 11)));
        now.set(now.get().plusSeconds(4));
        assertThat(registry.get("rembayung_slot_seats_taken").tag("slot", "1").gauge().value()).isEqualTo(10.0);

        now.set(now.get().plusSeconds(2));
        assertThat(registry.get("rembayung_slot_seats_taken").tag("slot", "1").gauge().value()).isEqualTo(11.0);
    }
}
