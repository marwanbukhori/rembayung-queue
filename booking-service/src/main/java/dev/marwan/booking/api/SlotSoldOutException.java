package dev.marwan.booking.api;

public class SlotSoldOutException extends RuntimeException {

    private final Long slotId;
    private final int requested;
    private final int remaining;

    public SlotSoldOutException(Long slotId, int requested, int remaining) {
        super("Slot " + slotId + " cannot seat " + requested + "; " + remaining + " remaining");
        this.slotId = slotId;
        this.requested = requested;
        this.remaining = remaining;
    }

    public Long getSlotId() { return slotId; }
    public int getRequested() { return requested; }
    public int getRemaining() { return remaining; }
}
