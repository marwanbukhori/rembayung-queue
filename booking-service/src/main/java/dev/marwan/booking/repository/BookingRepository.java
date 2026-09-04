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

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant cutoff);

    @Modifying
    @Query("update Booking b set b.status = dev.marwan.booking.domain.BookingStatus.EXPIRED "
         + "where b.id = :id and b.status = dev.marwan.booking.domain.BookingStatus.PENDING_DEPOSIT")
    int markExpiredIfPending(@Param("id") Long id);
}
