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

    @Scheduled(fixedDelayString = "PT30S")
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
     */
    @Transactional
    public int sweepExpired(Instant now) {
        List<Booking> stale = bookingRepository
                .findByStatusAndExpiresAtBefore(BookingStatus.PENDING_DEPOSIT, now);

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
