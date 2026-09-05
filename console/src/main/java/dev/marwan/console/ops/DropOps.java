package dev.marwan.console.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Starting a sandbox: a slot on booking-service, and a drop on the gate bound
 * to it.
 *
 * <h2>The drop id is the session</h2>
 * There is nothing else to store. No account, no cookie, no ownership record —
 * whoever holds the drop id can watch that drop, and the console holds nothing
 * on their behalf between requests. Both halves expire on their own: the drop
 * with its Redis key after thirty idle minutes, the slot with the sweeper that
 * reads sandbox_expires_at. Two trusted people do not need more than that, and
 * a session table would be a thing to keep correct for no gain.
 *
 * <h2>Why the order is slot first</h2>
 * A drop names the slot it sells, so the slot has to exist before the drop can
 * point at it. The reverse order would leave a drop pointing at nothing if the
 * second call failed — a sandbox that renders queue numbers next to an
 * unreadable slot. Failing the other way round leaves an unused slot row, which
 * the sweeper reaps two hours later.
 */
@RestController
@RequestMapping("/api/drops")
public class DropOps {

    private static final Logger log = LoggerFactory.getLogger(DropOps.class);

    /** Slow enough to watch a queue drain, fast enough not to be boring. */
    private static final int DEFAULT_ADMIT_RATE = 8;

    /** Above this the queue empties faster than the page can draw it. */
    private static final int MAX_ADMIT_RATE = 500;

    private final RestClient booking;
    private final RestClient gate;

    public DropOps(RestClient bookingClient, RestClient gateClient) {
        this.booking = bookingClient;
        this.gate = gateClient;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Sandbox create(@RequestBody(required = false) StartDrop request) {
        int admitRate = admitRateOf(request);
        long slotId = seedSlot();
        String dropId = createDrop(admitRate, slotId);
        log.info("Started sandbox drop {} on slot {} at {} admissions/s", dropId, slotId, admitRate);
        return new Sandbox(dropId, slotId, admitRate);
    }

    private int admitRateOf(StartDrop request) {
        if (request == null || request.admitRate() == null) {
            return DEFAULT_ADMIT_RATE;
        }
        int asked = request.admitRate();
        if (asked <= 0 || asked > MAX_ADMIT_RATE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "admitRate must be between 1 and " + MAX_ADMIT_RATE + " but was " + asked);
        }
        return asked;
    }

    private long seedSlot() {
        Map<String, Long> seeded;
        try {
            seeded = booking.post()
                    .uri("/internal/slots")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Long>>() { });
        } catch (Exception e) {
            throw unavailable("booking-service", e);
        }
        if (seeded == null || seeded.get("slotId") == null) {
            throw unavailable("booking-service", new IllegalStateException("no slotId in the response"));
        }
        return seeded.get("slotId");
    }

    private String createDrop(int admitRate, long slotId) {
        CreatedDrop drop;
        try {
            drop = gate.post()
                    .uri("/internal/drops")
                    .body(Map.of("admitRate", admitRate, "slotId", slotId))
                    .retrieve()
                    .body(CreatedDrop.class);
        } catch (Exception e) {
            throw unavailable("queue-gate", e);
        }
        if (drop == null || drop.id() == null) {
            throw unavailable("queue-gate", new IllegalStateException("no drop id in the response"));
        }
        return drop.id();
    }

    /**
     * 503 with the reason, not the console's usual 200-with-a-reason.
     *
     * A read that fails still has a page to draw. A creation that fails has
     * nothing: the button must not report a sandbox that does not exist.
     */
    private ResponseStatusException unavailable(String service, Exception cause) {
        log.warn("could not start a sandbox: {} failed: {}", service, cause.toString());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                service + " could not start a sandbox: " + cause.getMessage());
    }

    /** {@code {"admitRate": 8}}, or an empty body for the default. */
    public record StartDrop(Integer admitRate) { }

    /** What the browser needs to watch what it just made. */
    public record Sandbox(String dropId, long slotId, int admitRate) { }

    /** The gate returns the whole DropRecord; this is the part the console uses. */
    public record CreatedDrop(String id, Long slotId) { }
}
