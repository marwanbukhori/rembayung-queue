package dev.marwan.gate.queue;

import java.time.Duration;
import java.time.Instant;

/**
 * Admission is a pure function of time.
 *
 * There is no counter, no scheduler and no lock. Every replica of the gate
 * computes the identical answer from the clock, because this is arithmetic
 * rather than shared state — which is why admission needs no coordination
 * between replicas. A scheduled counter would advance at rate x replicaCount.
 */
public final class Admission {

    private Admission() { }

    /** How many tickets have been admitted by {@code now}. Zero before the drop opens. */
    public static long admittedBy(Instant now, Instant opensAt, int rate) {
        if (now.isBefore(opensAt)) {
            return 0;
        }
        long elapsedMillis = Duration.between(opensAt, now).toMillis();
        return Math.floorDiv(elapsedMillis * rate, 1000L);
    }

    /** The instant at which {@code ticket} becomes admitted. */
    public static Instant turnAt(long ticket, Instant opensAt, int rate) {
        long millis = Math.floorDiv(ticket * 1000L, (long) rate);
        return opensAt.plusMillis(millis);
    }

    public static boolean isAdmitted(long ticket, Instant now, Instant opensAt, int rate) {
        return ticket <= admittedBy(now, opensAt, rate);
    }

    /** True once the holder's five-minute window, measured from their turn, has lapsed. */
    public static boolean hasExpired(long ticket, Instant now, Instant opensAt,
                                     int rate, Duration window) {
        return now.isAfter(turnAt(ticket, opensAt, rate).plus(window));
    }
}
