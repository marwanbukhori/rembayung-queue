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

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExpirySweeperTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private ExpirySweeper expirySweeper;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbc;

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

    /**
     * Enters through sweep(), which is what the scheduler calls — not through
     * sweepExpired(), which is what every other test here calls.
     *
     * That distinction is the whole point. The two tests above passed for six
     * phases while the sweeper was completely broken in production, because
     * calling sweepExpired() on the injected bean goes through Spring's
     * transactional proxy, whereas sweep() calling sweepExpired() internally
     * goes straight to `this` and never enters it. Production threw
     * "No active transaction" on every run; the tests never saw it.
     *
     * A test that exercises a path production does not take cannot fail the way
     * production does.
     */
    @Test
    void sweepRunsInsideATransaction() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 12), "19:00", 250)).getId();
        BookingResult booked = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 5, "sweep-key-3"));

        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(5);

        // Backdate the hold with SQL rather than through the entity: Booking
        // deliberately exposes no setter for expiresAt, and adding one purely to
        // make a test convenient would weaken the domain to suit the test.
        jdbc.update("UPDATE bookings SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), booked.bookingId());

        // The scheduler's entry point. Before the fix this threw
        // InvalidDataAccessApiUsageException: No active transaction, because
        // findByIdForUpdate needs a transaction the proxy never opened.
        expirySweeper.sweep();

        assertThat(bookingRepository.findById(booked.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isZero();
    }

    /**
     * A sweep takes at most 100 holds, and the rest wait for the next pass.
     *
     * The bound is not tidiness. sweepExpired runs the whole batch in one
     * transaction and locks the slot row inside it, so the row stays locked
     * until the last booking in the list is done and every booking against that
     * slot waits behind it. Unbounded, a sold-out slot whose holds lapsed
     * together would hold that lock - and one of five pooled connections - for
     * the length of the run.
     */
    @Test
    void oneSweepTakesAtMostAHundredHoldsAndTheNextTakesTheRest() {
        int capacity = 150;
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2027, 6, 1), "19:00", capacity)).getId();

        for (int i = 0; i < capacity; i++) {
            bookingService.book(new BookingRequest(slotId, "+6015" + i, 1, "batch-" + i));
        }
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        assertThat(expirySweeper.sweepExpired(future)).isEqualTo(100);
        // Seats come back with the holds, so the slot is only partly released.
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken())
                .isEqualTo(capacity - 100);

        // The backlog still drains: rows leave the result set as they expire.
        assertThat(expirySweeper.sweepExpired(future)).isEqualTo(capacity - 100);
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isZero();
    }
}
