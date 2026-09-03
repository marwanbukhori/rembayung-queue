package dev.marwan.booking.api;

public class BookingNotFoundException extends RuntimeException {

    private final Long bookingId;

    public BookingNotFoundException(Long bookingId) {
        super("No booking with id " + bookingId);
        this.bookingId = bookingId;
    }

    public Long getBookingId() { return bookingId; }
}
