package dev.marwan.gate.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final DropRegistry drops;
    private final Clock clock;

    /** Shorter than any scrape interval: unifies reads within a scrape, never across two. */
    private static final Duration TTL = Duration.ofMillis(500);

    /**
     * Above this many memoised drops, drop the expired ones on the next miss.
     * Drops expire in Redis after half an hour of idleness; without this the
     * memo would be the one place a discarded sandbox left something behind.
     */
    private static final int PRUNE_ABOVE = 64;

    /**
     * One memo per drop, not one memo.
     *
     * A single field keyed by nothing was correct only while there was exactly
     * one drop. With a console handing every visitor their own, currentFor("A")
     * followed by currentFor("B") inside the TTL would answer B with A's queue
     * depth: one visitor shown another visitor's numbers, confidently and with
     * no error anywhere. Keying by drop keeps the consistency the memo exists
     * for and removes the confusion it would otherwise introduce.
     */
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    public QueueStateProvider(StringRedisTemplate redis, DropRegistry drops, Clock clock) {
        this.redis = redis;
        this.drops = drops;
        this.clock = clock;
    }

    /**
     * The canonical 21:00 drop — what the three Micrometer gauges report on.
     *
     * Kept as its own method so the gauges and their alert rules cannot be
     * pointed at somebody's sandbox by accident.
     */
    public QueueState current() {
        return currentFor(DropRegistry.DEFAULT_ID);
    }

    /**
     * One snapshot per drop per instant, briefly memoised.
     *
     * Not for load — a Redis GET per scrape is nothing. For consistency. The
     * three gauges call current() independently, and admittedBy() is a function
     * of the clock, so three uncached reads can straddle a second boundary and
     * publish an `admitted` that does not match the `admitted` used to derive
     * `waiting`. A dashboard where issued - admitted != waiting undermines
     * exactly the numbers this project asks people to trust.
     *
     * The TTL is deliberately shorter than any scrape interval, so a snapshot is
     * never stale between scrapes — it only unifies the reads within one.
     *
     * @throws UnknownDropException if the drop has expired or never existed
     */
    public QueueState currentFor(String dropId) {
        Instant now = clock.instant();
        Snapshot cached = snapshots.get(dropId);
        if (cached != null && Duration.between(cached.takenAt(), now).compareTo(TTL) < 0) {
            return cached.state();
        }
        DropRecord drop = drops.find(dropId)
                .orElseThrow(() -> new UnknownDropException(dropId));

        String raw = redis.opsForValue().get(QueueService.ticketCounter(dropId));
        long issued = raw == null ? 0L : Long.parseLong(raw);
        long admitted = Admission.admittedBy(now, drop.opensAt(), drop.admitRate());
        QueueState state = QueueState.of(issued, admitted, drop.ticketCap());
        snapshots.put(dropId, new Snapshot(state, now));
        pruneExpired(now);
        return state;
    }

    private void pruneExpired(Instant now) {
        if (snapshots.size() <= PRUNE_ABOVE) {
            return;
        }
        snapshots.values().removeIf(
                s -> Duration.between(s.takenAt(), now).compareTo(TTL) >= 0);
    }

    private record Snapshot(QueueState state, Instant takenAt) { }
}
