package dev.marwan.booking.service;

import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.repository.SlotRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlotStateProviderTest {

    private Slot slotWith(int capacity, int taken) {
        Slot slot = new Slot(LocalDate.of(2026, 10, 1), "19:00", capacity);
        if (taken > 0) {
            slot.takeSeats(taken);
        }
        return slot;
    }

    @Test
    void reportsRemainingAndZeroOversoldForAPartiallyFilledSlot() {
        SlotRepository repo = mock(SlotRepository.class);
        when(repo.findById(anyLong())).thenReturn(Optional.of(slotWith(250, 202)));

        SlotState state = new SlotStateProvider(repo).stateFor(1L).orElseThrow();

        assertThat(state.capacity()).isEqualTo(250);
        assertThat(state.seatsTaken()).isEqualTo(202);
        assertThat(state.remaining()).isEqualTo(48);
        assertThat(state.oversold()).isZero();
    }

    @Test
    void reportsZeroOversoldForAnExactlyFullSlot() {
        SlotRepository repo = mock(SlotRepository.class);
        when(repo.findById(anyLong())).thenReturn(Optional.of(slotWith(250, 250)));

        SlotState state = new SlotStateProvider(repo).stateFor(1L).orElseThrow();

        assertThat(state.remaining()).isZero();
        assertThat(state.oversold()).isZero();
    }

    // The database's ck_slots_seats CHECK constraint makes this state impossible
    // to persist, and the domain refuses to produce it. The record is still
    // tested directly, because the gauge exists to notice a state that "cannot
    // happen" — a gauge that cannot represent the failure it watches for is
    // decoration.
    @Test
    void reportsTheOverageIfASlotEverExceededItsCapacity() {
        SlotState state = new SlotState(1L, 250, 253, 0, 3);

        assertThat(state.oversold()).isEqualTo(3);
    }

    @Test
    void returnsEmptyForAnUnknownSlot() {
        SlotRepository repo = mock(SlotRepository.class);
        when(repo.findById(anyLong())).thenReturn(Optional.empty());

        assertThat(new SlotStateProvider(repo).stateFor(99L)).isEmpty();
    }
}
