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
        // admittedBy() is a pure function of elapsed time and keeps counting past
        // the last ticket ever issued. That is right for deciding whether ticket
        // N may pass, which is what it exists for, and wrong for both numbers
        // below.
        //
        // Clamping only the subtraction was the old fix, and it left `admitted`
        // raw: the deployed console read 17 tickets issued and 79,690 admitted,
        // which claims a door seventeen people walked through admitted seventy-
        // nine thousand. Worse, it was the one figure on the dashboard that kept
        // moving, so a drop with no traffic looked busy while every honest
        // counter sat at zero.
        //
        // Nobody can be admitted who has not arrived, so the count of arrivals
        // is the ceiling. Clamping here rather than at the call site also keeps
        // issued - admitted == waiting true by construction, which is the
        // property QueueStateProvider relies on when it says a dashboard that
        // fails it undermines the numbers this project asks people to trust.
        long trulyAdmitted = Math.min(admitted, ticketsIssued);
        return new QueueState(
                ticketsIssued,
                trulyAdmitted,
                ticketsIssued - trulyAdmitted,
                ticketCap);
    }
}
