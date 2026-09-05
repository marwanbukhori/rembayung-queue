package dev.marwan.console.state;

/**
 * The shape queue-gate returns from GET /internal/drops/{dropId}/state.
 *
 * The slot id is the field that matters here. Without it the console cannot
 * tell which slot a drop sells and has to be told — and the only thing a
 * browser could sensibly assume is the canonical slot, which would draw every
 * visitor's sandbox with the real restaurant's seats. The gate knows the
 * answer, so the gate says it.
 */
public record DropState(
        String dropId,
        Long slotId,
        long ticketsIssued,
        long admitted,
        long waiting,
        int ticketCap) { }
