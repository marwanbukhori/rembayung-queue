package dev.marwan.booking.repository;

import dev.marwan.booking.domain.Slot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    /**
     * Loads a slot under a row-level write lock (SELECT ... FOR UPDATE).
     * Every mutation of seats_taken — booking and expiry alike — MUST go
     * through this method, or the two can interleave and oversell the slot.
     *
     * Keep @Lock immediately above this method. An edit once inserted two new
     * methods between the annotation and this signature, which silently moved
     * the lock onto a read-only projection and left the booking path with no
     * row lock at all. Nothing failed to compile; ConcurrencyInvariantTest went
     * red with optimistic-locking errors, which is the only reason it was
     * noticed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.id = :id")
    Optional<Slot> findByIdForUpdate(@Param("id") Long id);

    /**
     * Slots the Prometheus gauges report on: real ones only.
     *
     * Sandboxes are excluded deliberately. Console sessions are unlimited by
     * design, and four gauges per abandoned demo would grow metric cardinality
     * without bound — a monitoring problem caused by monitoring.
     *
     * Deliberately NOT locked. This is a read for a dashboard; taking the
     * pessimistic lock here would put a metrics refresh into contention with
     * real bookings on the one row the whole system serialises on.
     */
    @Query("select s.id from Slot s where s.sandboxExpiresAt is null")
    List<Long> findPermanentSlotIds();

    /** Sandbox slots whose lifetime has lapsed. Read-only; the sweeper deletes by id. */
    @Query("select s.id from Slot s where s.sandboxExpiresAt is not null and s.sandboxExpiresAt < :now")
    List<Long> findExpiredSandboxIds(@Param("now") Instant now);
}
