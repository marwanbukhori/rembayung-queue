package dev.marwan.booking.repository;

import dev.marwan.booking.domain.Booking;
import dev.marwan.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query("delete from Booking b where b.slotId = :slotId")
    int deleteBySlotId(@Param("slotId") Long slotId);

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant cutoff);

    @Modifying
    @Query("update Booking b set b.status = dev.marwan.booking.domain.BookingStatus.EXPIRED "
         + "where b.id = :id and b.status = dev.marwan.booking.domain.BookingStatus.PENDING_DEPOSIT")
    int markExpiredIfPending(@Param("id") Long id);

    /**
     * Confirms a booking only if it is still PENDING_DEPOSIT, atomically.
     *
     * The mirror of markExpiredIfPending, and it exists for the same reason.
     * confirmDeposit used to read the booking, check the status in Java, and set
     * the field — a read-modify-write with no version column behind it. The
     * sweeper could expire the booking and release its seats in the gap, and the
     * dirty-check UPDATE would then write CONFIRMED straight over EXPIRED.
     *
     * The result was a CONFIRMED booking whose seats had already gone back into
     * inventory — sold twice — while rembayung_slot_oversold stayed at 0, because
     * the seat count itself was never wrong. Nothing would have alerted.
     *
     * Both statements are conditional on PENDING_DEPOSIT, so exactly one wins and
     * the loser sees 0 rows.
     */
    @Modifying
    @Query("update Booking b set b.status = dev.marwan.booking.domain.BookingStatus.CONFIRMED "
         + "where b.id = :id and b.status = dev.marwan.booking.domain.BookingStatus.PENDING_DEPOSIT")
    int markConfirmedIfPending(@Param("id") Long id);
}
