package dev.marwan.booking;

import dev.marwan.booking.api.BookingRequest;
import dev.marwan.booking.api.BookingResult;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;

    @Test
    void replayingTheSameKeyReturnsTheOriginalBookingAndTakesNoExtraSeats() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 5), "19:00", 250)).getId();

        BookingRequest request =
                new BookingRequest(slotId, "+60123456789", 3, "idem-fixed-key");

        BookingResult first = bookingService.book(request);
        BookingResult replay = bookingService.book(request);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.bookingId()).isEqualTo(first.bookingId());
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(3);
    }
}
