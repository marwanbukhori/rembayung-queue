package dev.marwan.booking.service;

import dev.marwan.booking.domain.Booking;
import dev.marwan.booking.domain.BookingStatus;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.BookingRepository;
import dev.marwan.booking.repository.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;

    public ExpirySweeper(BookingRepository bookingRepository, SlotRepository slotRepository) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
    }

    /**
     * The scheduled entry point.
     *
     * @Transactional is on THIS method, not only on sweepExpired, and the
     * distinction is not cosmetic. Spring applies @Transactional through a
     * proxy, and a call from one method of a bean to another goes directly to
     * `this` — the proxy is never entered. So when this method called
     * sweepExpired() without being transactional itself, sweepExpired ran with
     * no transaction at all, findByIdForUpdate could not take its pessimistic
     * lock, and every single invocation threw "No active transaction".
     *
     * That was live for the whole of Phase 1 through 6: 197 such errors on one
     * production pod, and not one "Expired N unpaid bookings" line ever
     * written. Unpaid holds never expired and their seats never returned to
     * inventory.
     *
     * The tests did not catch it because all three call sweepExpired() on the
     * injected bean, which IS the proxy — so they got a transaction and passed,
     * while production entered through sweep() and did not. A test that never
     * takes production's path cannot fail the way production does; see
     * ExpirySweeperTest.sweepRunsInsideATransaction.
     */
    @Scheduled(fixedDelayString = "PT30S")
    @Transactional
    public void sweep() {
        int expired = sweepExpired(Instant.now());
        if (expired > 0) {
            log.info("Expired {} unpaid bookings", expired);
        }
    }

    /**
     * Expires unpaid bookings whose hold has lapsed and returns their seats.
     *
     * Acquires the SAME pessimistic lock on the slot row that BookingService.book
     * uses. Without that, a release and a claim can interleave and oversell the slot.
     *
     * Claims each booking atomically: only one sweeper wins each booking's claim
     * via markExpiredIfPending, preventing double-release under concurrency.
     *
     * Bounded to 100 a pass, because that lock is held for the whole batch: the
     * slot row stays locked until this transaction commits, so every booking
     * against that slot waits for the last row in the list. Unbounded, a full
     * 250-seat slot whose holds lapsed together would block bookings for the
     * length of the sweep while occupying one of five connections. A backlog
     * drains across passes instead, 100 every 30 seconds.
     */
    @Transactional
    public int sweepExpired(Instant now) {
        List<Booking> stale = bookingRepository
                .findFirst100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                        BookingStatus.PENDING_DEPOSIT, now);

        int expired = 0;
        for (Booking booking : stale) {
            Slot slot = slotRepository.findByIdForUpdate(booking.getSlotId()).orElseThrow();
            if (bookingRepository.markExpiredIfPending(booking.getId()) == 1) {
                slot.releaseSeats(booking.getPartySize());
                expired++;
            }
        }
        return expired;
    }
}
