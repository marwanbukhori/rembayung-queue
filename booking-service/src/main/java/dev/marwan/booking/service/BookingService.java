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

    @Transactional
    public BookingResult confirmDeposit(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() != BookingStatus.PENDING_DEPOSIT) {
            throw new IllegalStateException(
                    "Booking " + bookingId + " is " + booking.getStatus()
                            + ", expected PENDING_DEPOSIT");
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        return new BookingResult(booking.getId(), booking.getStatus(), false);
    }
}
