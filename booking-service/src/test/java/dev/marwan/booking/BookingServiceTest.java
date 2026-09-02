package dev.marwan.booking;

import dev.marwan.booking.api.*;
import dev.marwan.booking.domain.BookingStatus;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingServiceTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;

    private Long seedSlot(int capacity) {
        return slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 1), String.valueOf(System.nanoTime() % 100000),
                        capacity)).getId();
    }

    @Test
    void bookingASeatMarksItPendingDeposit() {
        Long slotId = seedSlot(250);

        BookingResult result = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 2, "key-1"));

        assertThat(result.status()).isEqualTo(BookingStatus.PENDING_DEPOSIT);
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(2);
    }

    @Test
    void bookingBeyondCapacityIsRejected() {
        Long slotId = seedSlot(4);
        bookingService.book(new BookingRequest(slotId, "+60111111111", 4, "key-2"));

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest(slotId, "+60122222222", 1, "key-3")))
                .isInstanceOf(SlotSoldOutException.class);

        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(4);
    }

    @Test
    void bookingAnUnknownSlotIsRejected() {
        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest(999_999L, "+60123456789", 2, "key-4")))
                .isInstanceOf(SlotNotFoundException.class);
    }
}
