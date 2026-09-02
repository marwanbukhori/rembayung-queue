package dev.marwan.booking.api;

public class SlotNotFoundException extends RuntimeException {
    public SlotNotFoundException(Long slotId) {
        super("No slot with id " + slotId);
    }
}
