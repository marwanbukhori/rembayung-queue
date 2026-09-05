package dev.marwan.console.state;

/**
 * One snapshot of a drop, flattened for the browser.
 *
 * Every number here is fetched from the owning service's /internal endpoint,
 * never recomputed. seatsTaken and oversold in particular come from
 * SlotStateProvider, which is also what the Prometheus gauges and the
 * SlotOversold alert read — so the console cannot show a different answer from
 * the one that would page someone.
 */
public record DemoState(
        boolean available,
        String detail,
        String dropId,
        int capacity,
        int seatsTaken,
        int remaining,
        int oversold,
        long ticketsIssued,
        long admitted,
        long waiting) {

    public static DemoState unavailable(String detail) {
        return new DemoState(false, detail, null, 0, 0, 0, 0, 0, 0, 0);
    }
}
