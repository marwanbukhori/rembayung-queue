package dev.marwan.booking.api;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(Long bookingId) {
        super("No booking with id " + bookingId);
    }
}
