package dev.marwan.console.state;

import dev.marwan.console.ConsoleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads one drop's numbers from the two services that own them.
 *
 * Nothing here computes a seat count or a queue depth. booking-service owns
 * seats and oversold, queue-gate owns tickets and waiting, and this class does
 * no arithmetic on either — a console that recomputed would eventually disagree
 * with the alert that pages someone, and then it would be the console people
 * stopped trusting.
 *
 * <h2>Why unreachable is not an error</h2>
 * Pods restart, sandbox drops expire after thirty idle minutes, and the Always
 * Free database idles out between demos. All of that is ordinary. A 500 here
 * would make the console the second casualty of every blip, so every failure
 * becomes {@link DemoState#unavailable(String)} carrying the reason, and the
 * page renders the reason where the numbers were.
 *
 * <h2>Why the cache is one second</h2>
 * The page polls every two seconds and any number of people may have it open.
 * Without a cache, viewer count multiplies directly into load on the services
 * this console exists to observe. One second is short enough that the numbers
 * still look live and long enough that a hundred viewers cost the services one
 * read rather than a hundred.
 */
@Service
public class DemoStateProvider {

    private static final Logger log = LoggerFactory.getLogger(DemoStateProvider.class);

    /**
     * Cached aggregates are keyed by drop and slot, so one visitor's sandbox
     * cannot be served another's numbers. Entries this old are dropped wholesale
     * rather than reused, which bounds the map without a scheduled sweeper.
     */
    private static final Duration ENTRY_MAX_AGE = Duration.ofMinutes(5);

    /** A hostile query string must not be able to grow the map without bound. */
    private static final int MAX_ENTRIES = 512;

    private final RestClient booking;
    private final RestClient gate;
    private final ConsoleProperties properties;
    private final Clock clock;
    private final Map<Key, Cached> cache = new ConcurrentHashMap<>();

    public DemoStateProvider(RestClient bookingClient, RestClient gateClient,
                             ConsoleProperties properties, Clock clock) {
        this.booking = bookingClient;
        this.gate = gateClient;
        this.properties = properties;
        this.clock = clock;
    }

    public DemoState currentFor(String dropId, long slotId) {
        Key key = new Key(dropId, slotId);
        Instant now = clock.instant();
        Cached hit = cache.get(key);
        if (hit != null && Duration.between(hit.at(), now).compareTo(properties.cacheTtl()) < 0) {
            return hit.state();
        }
        DemoState fresh = fetch(dropId, slotId);
        store(key, new Cached(now, fresh));
        return fresh;
    }

    private DemoState fetch(String dropId, long slotId) {
        SlotState slot;
        try {
            slot = booking.get()
                    .uri("/internal/slots/{id}", slotId)
                    .retrieve()
                    .body(SlotState.class);
        } catch (Exception e) {
            return unreachable("booking-service", e);
        }
        if (slot == null) {
            return DemoState.unavailable("booking-service has no slot " + slotId);
        }

        QueueState queue;
        try {
            queue = gate.get()
                    .uri("/internal/drops/{dropId}/state", dropId)
                    .retrieve()
                    .body(QueueState.class);
        } catch (Exception e) {
            return unreachable("queue-gate", e);
        }
        if (queue == null) {
            return DemoState.unavailable("queue-gate has no drop " + dropId);
        }

        return new DemoState(true, null, dropId,
                slot.capacity(), slot.seatsTaken(), slot.remaining(), slot.oversold(),
                queue.ticketsIssued(), queue.admitted(), queue.waiting());
    }

    /**
     * The reason is written for someone reading the page, not for a stack trace.
     * "queue-gate did not answer: 404 Not Found" tells a visitor their sandbox
     * expired; a NestedRuntimeException class name tells them nothing.
     */
    private DemoState unreachable(String service, Exception e) {
        log.warn("console could not read {}: {}", service, e.toString());
        String cause = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return DemoState.unavailable(service + " did not answer: " + trimmed(cause));
    }

    private static String trimmed(String message) {
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
    }

    private void store(Key key, Cached entry) {
        cache.put(key, entry);
        if (cache.size() > MAX_ENTRIES) {
            cache.entrySet().removeIf(e ->
                    Duration.between(e.getValue().at(), entry.at()).compareTo(ENTRY_MAX_AGE) > 0);
        }
    }

    private record Key(String dropId, long slotId) { }

    private record Cached(Instant at, DemoState state) { }
}
