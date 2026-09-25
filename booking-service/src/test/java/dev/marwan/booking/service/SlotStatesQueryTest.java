package dev.marwan.booking.service;

import dev.marwan.booking.OracleTestBase;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gauges' one query, against real Oracle: every permanent slot with its
 * state, keyed by id, and no sandboxes - their expiry is what keeps the gauge
 * label set bounded.
 */
class SlotStatesQueryTest extends OracleTestBase {

    @Autowired
    private SlotRepository slots;

    @Autowired
    private SlotStateProvider provider;

    @Test
    void permanentStatesAreEveryPermanentSlotInOneReadAndNoSandboxes() {
        Slot permanent = new Slot(LocalDate.of(2026, 10, 1), "21:00", 250);
        permanent.takeSeats(12);
        long permanentId = slots.save(permanent).getId();

        Slot sandbox = new Slot(LocalDate.of(2026, 10, 2), "21:00", 250);
        sandbox.expireAsSandboxAt(Instant.now().plusSeconds(3600));
        long sandboxId = slots.save(sandbox).getId();

        Map<Long, SlotState> states = provider.permanentStates();

        assertThat(states).containsKey(permanentId).doesNotContainKey(sandboxId);
        assertThat(states.get(permanentId).seatsTaken()).isEqualTo(12);
        assertThat(states.get(permanentId).remaining()).isEqualTo(238);
    }
}
