package dev.marwan.booking.domain;

/**
 * A slot's state at one instant, computed once and projected several ways.
 *
 * Micrometer gauges read this today. Phase 8's public JSON endpoint reads the
 * same record, so the number on a public dashboard and the number in an alert
 * rule cannot drift apart — which matters because seatsTaken versus capacity is
 * the claim this whole project makes.
 *
 * Field names match metric names minus the rembayung_ prefix, on purpose.
 */
public record SlotState(
        long slotId,
        int capacity,
        int seatsTaken,
        int remaining,
        int oversold) {

    public static SlotState of(long slotId, int capacity, int seatsTaken) {
        return new SlotState(
                slotId,
                capacity,
                seatsTaken,
                Math.max(0, capacity - seatsTaken),
                Math.max(0, seatsTaken - capacity));
    }
}
