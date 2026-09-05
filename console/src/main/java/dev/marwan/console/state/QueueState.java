package dev.marwan.console.state;

/** The shape queue-gate returns from GET /internal/drops/{dropId}/state. */
public record QueueState(
        long ticketsIssued,
        long admitted,
        long waiting,
        int ticketCap) { }
