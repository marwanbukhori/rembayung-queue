package dev.marwan.console.state;

/**
 * The shape booking-service returns from GET /internal/slots/{id}.
 *
 * Declared here rather than shared as a library on purpose: the console is a
 * client of an HTTP contract, not a module of the booking service, and a shared
 * jar would let a field rename in one repository silently become a compile
 * error in the other's release build.
 */
public record SlotState(
        long slotId,
        int capacity,
        int seatsTaken,
        int remaining,
        int oversold) { }
