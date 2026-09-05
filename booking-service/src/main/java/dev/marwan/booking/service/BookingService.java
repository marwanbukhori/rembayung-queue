package dev.marwan.booking.service;

import dev.marwan.booking.api.*;
import dev.marwan.booking.domain.Booking;
import dev.marwan.booking.domain.BookingStatus;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.BookingRepository;
import dev.marwan.booking.repository.SlotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class BookingService {

    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final long depositCentsPerHead;
    private final Duration holdTtl;

    /**
     * This bean through its own proxy, because the recovery below has to run in
     * a different transaction from the one that failed. Calling the method
     * directly would be a plain Java call: it would join the rolled-back
     * transaction instead of starting a new one, and read nothing. The same
     * self-invocation trap silently disabled the transaction on the expiry
     * sweeper's scheduled method.
     */
    private final BookingService self;

    public BookingService(SlotRepository slotRepository,
                          BookingRepository bookingRepository,
                          @Value("${booking.deposit-cents-per-head}") long depositCentsPerHead,
                          @Value("${booking.hold-ttl}") Duration holdTtl,
                          @Lazy BookingService self) {
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
        this.depositCentsPerHead = depositCentsPerHead;
        this.holdTtl = holdTtl;
        this.self = self;
    }

    /**
     * Book a seat, at most once per idempotency key.
     *
     * The check below is a read followed by a write, and between the two a
     * second request carrying the same key can pass through the same gap: both
     * read "no prior booking", both claim a seat, and both insert.
     * uq_bookings_idempotency is what stops the second seat from being sold, and
     * the rollback returns it - so the race cannot oversell. It can only decide
     * whether a retry is answered with the original booking or with an error,
     * and answering a retry with an error defeats the point of the key.
     *
     * Widening the lock to cover the read would serialise every booking on one
     * row regardless of key, so the duplicate is caught rather than prevented:
     * the constraint has already established that a prior booking exists, and
     * this reads it back and replays it. That read must be its own transaction,
     * which is why it goes through the proxy.
     */
    public BookingResult book(BookingRequest request) {
        try {
            return self.claimSeat(request);
        } catch (DataIntegrityViolationException e) {
            // Only a duplicate key is recoverable here. Any other constraint -
            // ck_slots_seats above all - means something this method believes
            // about the data is wrong, and must keep travelling.
            return self.replayOf(request.idempotencyKey()).orElseThrow(() -> e);
        }
    }

    /**
     * Reads a booking already committed under this key, in a transaction of its
     * own so it can see a row the caller's rolled-back transaction cannot.
     */
    @Transactional(readOnly = true)
    public Optional<BookingResult> replayOf(String idempotencyKey) {
        return bookingRepository.findByIdempotencyKey(idempotencyKey)
                .map(prior -> new BookingResult(prior.getId(), prior.getStatus(), true));
    }

    @Transactional
    public BookingResult claimSeat(BookingRequest request) {
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
