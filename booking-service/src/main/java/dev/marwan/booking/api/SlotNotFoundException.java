package dev.marwan.booking.api;

public class SlotNotFoundException extends RuntimeException {

    private final Long slotId;

    public SlotNotFoundException(Long slotId) {
        super("No slot with id " + slotId);
        this.slotId = slotId;
    }

    public Long getSlotId() { return slotId; }
}
