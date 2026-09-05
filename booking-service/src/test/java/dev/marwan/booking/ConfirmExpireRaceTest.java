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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A confirmed booking must never have its seats returned to inventory.
 *
 * confirmDeposit used to read the booking, check PENDING_DEPOSIT in Java, and
 * set the field. Booking carries no @Version, so the sweeper could expire it and
 * release its seats in the gap, and the dirty-check UPDATE then wrote CONFIRMED
 * over EXPIRED. The booking was confirmed and its seats were back on sale.
 *
 * The reason this is worth its own test rather than a line in an existing one:
 * `rembayung_slot_oversold` stays at 0 throughout. seats_taken is never wrong —
 * it is *too low*. The invariant the whole project is built around holds
 * perfectly while a guest who paid loses their table, so no alert fires and no
 * existing assertion notices.
 */
class ConfirmExpireRaceTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private ExpirySweeper expirySweeper;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void confirmingAnAlreadyExpiredBookingIsRefusedRatherThanOverwritingIt() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 20), "19:00", 250)).getId();
        BookingResult booked = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 4, "race-key-1"));

        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(4);

        // The sweeper wins the race: the hold lapses and the seats go back.
        jdbc.update("UPDATE bookings SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), booked.bookingId());
        assertThat(expirySweeper.sweepExpired(Instant.now())).isEqualTo(1);
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isZero();

        // The customer's payment lands a moment later. Before the fix this
        // overwrote EXPIRED with CONFIRMED and left the seats released.
        assertThatThrownBy(() -> bookingService.confirmDeposit(booked.bookingId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXPIRED");

        assertThat(bookingRepository.findById(booked.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        // The seats stay released, and critically the booking is NOT confirmed —
        // the two facts agree. A CONFIRMED booking with released seats is the
        // state that sells a table twice.
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isZero();
    }

    @Test
    void theOrdinaryConfirmationStillWorks() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 21), "19:00", 250)).getId();
        BookingResult booked = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 4, "race-key-2"));

        BookingResult confirmed = bookingService.confirmDeposit(booked.bookingId());

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(4);
        // And a confirmed booking is no longer sweepable.
        assertThat(expirySweeper.sweepExpired(Instant.now().plusSeconds(7200))).isZero();
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(4);
    }

    /**
     * Races confirmation against the sweeper and checks the accounting identity.
     *
     * Be honest about what this does and does not prove: reverting confirmDeposit
     * to its read-check-write form does NOT make this fail. Forty bookings across
     * sixteen threads did not produce the interleaving, because the sweeper takes
     * the SLOT lock before it touches a booking, which serialises it against the
     * confirm path far more than the code reads like it does.
     *
     * So this is not a regression test. It is a standing assertion of the
     * identity, kept because the identity is the thing that would break if the
     * claim ever stopped being atomic, and because an unreproduced race is still
     * worth a guard — the fix costs nothing and a read-check-write cannot be
     * argued safe.
     *
     * The bug is an interleaving, so this races confirmation against the sweeper
     * for real and then checks an accounting identity that the bug violates:
     *
     *     seats_taken  ==  sum of party sizes of every booking NOT expired
     *
     * A CONFIRMED booking whose seats were released breaks that equation while
     * leaving rembayung_slot_oversold at 0 — seats_taken is not too high, it is
     * too low. That is why no existing assertion noticed.
     */
    @Test
    void seatsAlwaysAccountForEveryBookingThatSurvived() throws Exception {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 22), "19:00", 250)).getId();

        int bookings = 40;
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < bookings; i++) {
            ids.add(bookingService.book(
                    new BookingRequest(slotId, "+6013" + i, 2, "race-" + i)).bookingId());
        }
        // Every hold has lapsed, so the sweeper is entitled to take all of them.
        jdbc.update("UPDATE bookings SET expires_at = ? WHERE slot_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), slotId);

        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var gun = new java.util.concurrent.CountDownLatch(1);
        var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();

        for (Long id : ids) {
            tasks.add(pool.submit(() -> {
                gun.await();
                try {
                    bookingService.confirmDeposit(id);
                } catch (IllegalStateException expected) {
                    // The sweeper got there first. That is a correct outcome.
                }
                return null;
            }));
        }
        for (int i = 0; i < 4; i++) {
            tasks.add(pool.submit(() -> {
                gun.await();
                for (int r = 0; r < 15; r++) {
                    expirySweeper.sweepExpired(Instant.now());
                }
                return null;
            }));
        }

        gun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(3, java.util.concurrent.TimeUnit.MINUTES)).isTrue();
        for (var t : tasks) {
            t.get();
        }

        int seatsTaken = slotRepository.findById(slotId).orElseThrow().getSeatsTaken();
        Integer owed = jdbc.queryForObject(
                "SELECT NVL(SUM(party_size),0) FROM bookings WHERE slot_id = ? AND status <> 'EXPIRED'",
                Integer.class, slotId);

        assertThat(seatsTaken)
                .as("every surviving booking must still own its seats")
                .isEqualTo(owed);
    }
}
