package dev.marwan.booking.repository;

import dev.marwan.booking.domain.Booking;
import dev.marwan.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant cutoff);
}
