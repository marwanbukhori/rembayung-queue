package dev.marwan.booking.repository;

import dev.marwan.booking.domain.Slot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    /**
     * Loads a slot under a row-level write lock (SELECT ... FOR UPDATE).
     * Every mutation of seats_taken — booking and expiry alike — MUST go
     * through this method, or the two can interleave and oversell the slot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.id = :id")
    Optional<Slot> findByIdForUpdate(@Param("id") Long id);
}
