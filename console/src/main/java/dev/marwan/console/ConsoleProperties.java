package dev.marwan.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the console reads from, and how hard it is allowed to read.
 *
 * @param bookingBaseUrl  booking-service, which owns seats and oversold
 * @param gateBaseUrl     queue-gate, which owns tickets, admitted and waiting,
 *                        and which the in-cluster load Job is pointed at
 * @param canonicalDrop   the drop the public page shows when none is asked for
 * @param canonicalSlot   the slot that drop sells; slot 1 in every environment
 * @param timeout         per-request budget against either service
 * @param cacheTtl        how long one aggregate is reused across viewers
 * @param namespace       the namespace pod health and the quota are read from
 * @param accessKey       the one key that opens the console; unset means shut
 * @param quota           which ResourceQuota the CPU budget is read from
 * @param k6Image         the image a load run uses; pinned, not :latest
 * @param pool            the Oracle connection budget, which is arithmetic on
 *                        two numbers this project chose rather than a metric
 *                        anything exports
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
        String accessKey,
        String quota,
        String k6Image,
        Pool pool) {

    /**
     * Replicas times the pool each one opens, against what the database will
     * actually give out.
     *
     * Nothing exports this as a metric — Hikari's gauges are per pod and the
     * cap belongs to Oracle, not to us — so the console does the multiplication
     * and says which numbers it multiplied. That is honest in a way a made-up
     * live figure would not be.
     *
     * @param deployment  whose replicas open the connections
     * @param perReplica  spring.datasource.hikari.maximum-pool-size
     * @param cap         where Oracle Always Free stops handing out sessions
     */
    public record Pool(String deployment, int perReplica, int cap) { }
}
