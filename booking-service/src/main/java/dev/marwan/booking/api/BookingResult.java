package dev.marwan.booking.api;

import dev.marwan.booking.domain.BookingStatus;

public record BookingResult(Long bookingId, BookingStatus status, boolean idempotentReplay) { }
