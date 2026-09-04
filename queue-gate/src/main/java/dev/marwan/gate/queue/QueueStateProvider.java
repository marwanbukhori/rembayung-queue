package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads queue state without disturbing it.
 *
 * Note GET, never INCR — a metrics scrape that issued a ticket would consume
 * one of the 250 the drop has to give away, and the scrape interval would
 * quietly set the pace at which the queue sold out.
 */
@Service
public class QueueStateProvider {

    private final StringRedisTemplate redis;
    private final DropProperties drop;
    private final Clock clock;

    /** Shorter than any scrape interval: unifies reads within a scrape, never across two. */
    private static final Duration TTL = Duration.ofMillis(500);

    private volatile Snapshot snapshot;

    public QueueStateProvider(StringRedisTemplate redis, DropProperties drop, Clock clock) {
        this.redis = redis;
        this.drop = drop;
        this.clock = clock;
    }

    /**
     * One snapshot per instant, briefly memoised.
     *
     * Not for load — three Redis GETs per scrape is nothing. For consistency.
     * The three gauges call this independently, and admittedBy() is a function
     * of the clock, so three uncached reads can straddle a second boundary and
     * publish an `admitted` that does not match the `admitted` used to derive
     * `waiting`. A dashboard where issued - admitted != waiting undermines
     * exactly the numbers this project asks people to trust.
     *
     * The TTL is deliberately shorter than any scrape interval, so a snapshot is
     * never stale between scrapes — it only unifies the reads within one.
     */
    public QueueState current() {
        Instant now = clock.instant();
        Snapshot cached = snapshot;
        if (cached != null && Duration.between(cached.takenAt(), now).compareTo(TTL) < 0) {
            return cached.state();
        }
        String raw = redis.opsForValue().get(QueueService.TICKET_COUNTER);
        long issued = raw == null ? 0L : Long.parseLong(raw);
        long admitted = Admission.admittedBy(now, drop.opensAt(), drop.admitRate());
        QueueState state = QueueState.of(issued, admitted, drop.ticketCap());
        snapshot = new Snapshot(state, now);
        return state;
    }

    private record Snapshot(QueueState state, Instant takenAt) { }
}
