package dev.marwan.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the console reads from, and how hard it is allowed to read.
 *
 * @param bookingBaseUrl  booking-service, which owns seats and oversold
 * @param gateBaseUrl     queue-gate, which owns tickets, admitted and waiting
 * @param canonicalDrop   the drop the public page shows when none is asked for
 * @param canonicalSlot   the slot that drop sells; slot 1 in every environment
 * @param timeout         per-request budget against either service
 * @param cacheTtl        how long one aggregate is reused across viewers
 * @param namespace       the namespace pod health is read from
 * @param accessKey       the one key that opens the console; unset means shut
 */
@ConfigurationProperties(prefix = "console")
public record ConsoleProperties(
        String bookingBaseUrl,
        String gateBaseUrl,
        String canonicalDrop,
        long canonicalSlot,
        Duration timeout,
        Duration cacheTtl,
        String namespace,
        String accessKey) { }
