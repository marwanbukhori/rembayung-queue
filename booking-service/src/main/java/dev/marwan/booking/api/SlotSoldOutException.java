package dev.marwan.booking.api;

public class SlotSoldOutException extends RuntimeException {
    public SlotSoldOutException(Long slotId, int requested, int remaining) {
        super("Slot " + slotId + " cannot seat " + requested + "; " + remaining + " remaining");
    }
}
