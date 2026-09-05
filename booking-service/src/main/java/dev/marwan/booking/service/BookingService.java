package dev.marwan.booking.service;

import dev.marwan.booking.api.*;
import dev.marwan.booking.domain.Booking;
import dev.marwan.booking.domain.BookingStatus;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.BookingRepository;
import dev.marwan.booking.repository.SlotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class BookingService {

    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final long depositCentsPerHead;
    private final Duration holdTtl;

    public BookingService(SlotRepository slotRepository,
                          BookingRepository bookingRepository,
                          @Value("${booking.deposit-cents-per-head}") long depositCentsPerHead,
                          @Value("${booking.hold-ttl}") Duration holdTtl) {
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
        this.depositCentsPerHead = depositCentsPerHead;
        this.holdTtl = holdTtl;
    }

    @Transactional
    public BookingResult book(BookingRequest request) {
        var existing = bookingRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Booking prior = existing.get();
            return new BookingResult(prior.getId(), prior.getStatus(), true);
        }

        Slot slot = slotRepository.findByIdForUpdate(request.slotId())
                .orElseThrow(() -> new SlotNotFoundException(request.slotId()));

        if (!slot.canAccommodate(request.partySize())) {
            throw new SlotSoldOutException(
                    request.slotId(), request.partySize(), slot.remainingSeats());
        }

        slot.takeSeats(request.partySize());

        Instant now = Instant.now();
        Booking booking = bookingRepository.save(new Booking(
                slot.getId(),
                request.phone(),
                request.partySize(),
                depositCentsPerHead * request.partySize(),
                request.idempotencyKey(),
                now,
                now.plus(holdTtl)));

        return new BookingResult(booking.getId(), booking.getStatus(), false);
    }

    /**
     * Confirms a deposit, claiming the booking atomically rather than reading it,
     * checking it, and writing it back.
     *
     * The read-then-write version raced the sweeper: it read PENDING_DEPOSIT,
     * the sweeper expired the booking and returned its seats to the slot, and
     * the dirty-check UPDATE then wrote CONFIRMED over EXPIRED. The booking was
     * confirmed and its seats were back on sale — sold twice — with
     * rembayung_slot_oversold still reading 0, because seats_taken itself was
     * never wrong. The invariant this project is built around would have held
     * while the guest lost their table.
     */
    @Transactional
    public BookingResult confirmDeposit(Long bookingId) {
        if (bookingRepository.markConfirmedIfPending(bookingId) == 1) {
            return new BookingResult(bookingId, BookingStatus.CONFIRMED, false);
        }

        // Nothing was claimed. Either the booking is gone, or something else
        // moved it out of PENDING_DEPOSIT first — usually the sweeper. Re-read to
        // tell the caller which, since "expired while you were paying" and "no
        // such booking" need different answers.
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        throw new IllegalStateException(
                "Booking " + bookingId + " is " + booking.getStatus()
                        + ", expected PENDING_DEPOSIT");
    }
}
