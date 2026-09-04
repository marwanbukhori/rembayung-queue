package dev.marwan.gate.queue;

/**
 * The queue's state at one instant.
 *
 * Phase 8's public endpoint reads this same record, so the dashboard and the
 * metrics cannot disagree. Field names match metric names minus the rembayung_
 * prefix.
 */
public record QueueState(
        long ticketsIssued,
        long admitted,
        long waiting,
        int ticketCap) {

    public static QueueState of(long ticketsIssued, long admitted, int ticketCap) {
        // admittedBy() is a pure function of elapsed time and keeps counting
        // past the last ticket ever issued, so this subtraction can go negative.
        // A queue of -412 people is not a thing.
        return new QueueState(
                ticketsIssued,
                admitted,
                Math.max(0, ticketsIssued - admitted),
                ticketCap);
    }
}
