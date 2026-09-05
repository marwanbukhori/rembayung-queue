package dev.marwan.gate.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class QueueService {

    static final String ADMIT_PREFIX = "admit:";

    /**
     * The canonical drop's counter, kept as a named constant because the metrics
     * gauges report on the 21:00 queue rather than on somebody's sandbox.
     */
    static final String TICKET_COUNTER = ticketCounter(DropRegistry.DEFAULT_ID);

    private final StringRedisTemplate redis;
    private final DropRegistry drops;
    private final Clock clock;

    public QueueService(StringRedisTemplate redis, DropRegistry drops, Clock clock) {
        this.redis = redis;
        this.drops = drops;
        this.clock = clock;
    }

    /** One counter per drop, so two drops never share a ticket sequence. */
    static String ticketCounter(String dropId) {
        return "queue:" + dropId + ":ticket";
    }

    /**
     * Joins a named drop. The old no-argument form is gone: every join belongs
     * to exactly one drop, and defaulting silently would let a console bug send
     * a visitor's traffic into the canonical 21:00 queue.
     */
    public JoinResult join(String dropId) {
        DropRecord drop = drops.find(dropId).orElseThrow(() -> new UnknownDropException(dropId));
        Instant now = clock.instant();

        if (now.isBefore(drop.opensAt())) {
            throw new DropNotOpenException(Duration.between(now, drop.opensAt()).toSeconds());
        }
        if (now.isAfter(drop.closesAt())) {
            throw new DropClosedException();
        }

        Long ticket = redis.opsForValue().increment(ticketCounter(dropId));
        if (ticket == null || ticket > drop.ticketCap()) {
            throw new SoldOutException();
        }
        if (ticket == 1L) {
            // The rush starts here, not when the drop was created. Admission is
            // elapsed time times a rate, so without this it banks capacity while
            // nobody is queueing and the first arrivals are admitted on contact.
            drops.recordFirstArrival(dropId, now);
            // find() above ran touch() before these keys existed, and EXPIRE on a
            // missing key does nothing. Without this they would carry no expiry
            // until some later request touched the drop again, so a drop that saw
            // exactly one join would leave them behind forever.
            drops.touch(dropId);
        }

        String token = UUID.randomUUID().toString();
        // The drop id travels with the ticket so the token is self-describing:
        // position() and consume() resolve the drop without a parameter, which
        // is what keeps GET /queue/{token} and POST /bookings unchanged.
        redis.opsForValue().set(ADMIT_PREFIX + token,
                dropId + ":" + ticket, drop.ticketTtl());

        long admitted = Admission.admittedBy(now, drops.admissionStartsAt(drop), drop.admitRate());
        long position = Math.max(0, ticket - admitted);

        return new JoinResult(token, ticket, position,
                (double) position / drop.admitRate(),
                position == 0);
    }
}
