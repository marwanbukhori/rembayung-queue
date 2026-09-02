package dev.marwan.booking;

import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SlotRepositoryTest extends OracleTestBase {

    @Autowired
    private SlotRepository slotRepository;

    @Test
    @Transactional
    void findByIdForUpdateReturnsTheSlot() {
        Slot saved = slotRepository.save(new Slot(LocalDate.of(2026, 11, 1), "19:00", 250));

        Slot locked = slotRepository.findByIdForUpdate(saved.getId()).orElseThrow();

        assertThat(locked.getCapacity()).isEqualTo(250);
        assertThat(locked.getSeatsTaken()).isZero();
        assertThat(locked.remainingSeats()).isEqualTo(250);
    }

    @Test
    @Transactional
    void takeSeatsIncrementsAndReleaseSeatsDecrements() {
        Slot slot = new Slot(LocalDate.of(2026, 11, 2), "19:00", 250);
        slot.takeSeats(4);
        assertThat(slot.getSeatsTaken()).isEqualTo(4);
        slot.releaseSeats(3);
        assertThat(slot.getSeatsTaken()).isEqualTo(1);
    }
}
