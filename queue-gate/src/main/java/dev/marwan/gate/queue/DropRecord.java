package dev.marwan.gate.queue;

import java.time.Duration;
import java.time.Instant;

/**
 * One drop: a window, a rate, a ticket budget, and the slot its bookings land in.
 *
 * This exists because the gate previously had exactly one drop, defined by
 * environment variables and changeable only by restarting a pod. That was fine
 * for a single scheduled 21:00 opening and impossible for a console where any
 * visitor can start their own at any moment.
 *
 * Held in Redis rather than configuration precisely so creating one is a write,
 * not a redeploy.
 */
public record DropRecord(
        String id,
        Instant opensAt,
        Instant closesAt,
        int ticketCap,
        int admitRate,
        Duration admissionWindow,
        Duration ticketTtl,
        Long slotId) { }
