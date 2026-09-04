package dev.marwan.booking;

import dev.marwan.booking.api.BookingRequest;
import dev.marwan.booking.api.BookingResult;
import dev.marwan.booking.domain.BookingStatus;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.BookingRepository;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.BookingService;
import dev.marwan.booking.service.ExpirySweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExpirySweeperTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private ExpirySweeper expirySweeper;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;

    @Test
    void expiredPendingBookingsReleaseTheirSeats() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 10), "19:00", 250)).getId();
        BookingResult booked = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 5, "sweep-key-1"));

        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(5);

        // Sweep with a clock far enough forward that the 10-minute hold has lapsed.
        int expired = expirySweeper.sweepExpired(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(expired).isEqualTo(1);
        assertThat(bookingRepository.findById(booked.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isZero();
    }

    @Test
    void confirmedBookingsAreNeverSwept() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 11), "19:00", 250)).getId();
        BookingResult booked = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 5, "sweep-key-2"));
        bookingService.confirmDeposit(booked.bookingId());

        int expired = expirySweeper.sweepExpired(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(expired).isZero();
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(5);
    }
}
