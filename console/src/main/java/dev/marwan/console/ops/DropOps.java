package dev.marwan.console.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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
    /**
     * One a second, which is what this database can actually commit.
     *
     * It was 8, from a calculation that divided twenty connections by a 2.7s
     * round trip. That models the pool as the constraint, and the pool is not
     * the constraint: every booking for a sitting takes a pessimistic lock on
     * the same slot row, so they serialise, and each one holds that lock across
     * a round trip to an Oracle instance a region away.
     *
     * Measured against the deployed cluster - thirty admitted holders booking
     * at once - twelve committed and eighteen came back 503 BOOKING_SERVICE_BUSY
     * with the pool exhausted. A full run at 8/s created sixteen bookings and
     * rejected a hundred and eighty-four. That is the system defending itself
     * correctly, and it is a terrible first thing to show somebody: the default
     * has to be a rate the database can absorb, so that what a visitor sees
     * first is a queue draining and seats filling. Breaking it is what the 200
     * option is for, chosen deliberately.
     */
    private static final int DEFAULT_ADMIT_RATE = 1;

    /** Above this the queue empties faster than the page can draw it. */
    private static final int MAX_ADMIT_RATE = 500;

    /**
     * The three rates the console offers, and what each one demonstrates.
     *
     * <ul>
     *   <li><b>1/s</b> — the default, and what this database actually commits:
     *       bookings for one sitting serialise on one row. The queue drains and
     *       the seats fill.</li>
     *   <li><b>8/s</b> — eight times what the database commits, so the queue
     *       empties faster than the seats fill and the overflow is refused.
     *       Measured: a full run at this rate created 16 bookings and took 503
     *       for 184. This used to be the default, and used to be justified as 20
     *       connections divided by a 2.7s round trip - which models the pool as
     *       the constraint when the constraint is one row lock held across that
     *       round trip.</li>
     *   <li><b>200/s</b> — more than the database can absorb. The pool
     *       exhausts, booking-service refuses with 503 and Retry-After, and
     *       <b>oversold stays at zero anyway</b>.</li>
     * </ul>
     *
     * That third one is offered on purpose and labelled rather than hidden
     * behind a warning. It is the most persuasive thing this console can show:
     * the system under genuine overload, refusing work correctly instead of
     * breaking its own invariant. A control that only let you pick safe values
     * would have nothing to prove.
     */
    private static final List<Integer> OFFERED_RATES = List.of(1, 8, 200);

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

    /**
     * Change a drop's admission rate while it is open.
     *
     * A write to the DropRecord in Redis, which is why this is possible at all:
     * the rate used to be an environment variable, and changing it meant a
     * redeploy. It is per drop, so a visitor turning theirs up to 200 does not
     * touch the canonical 21:00 drop — and the gate refuses that one outright,
     * because the canonical drop is built from configuration and is not stored.
     */
    @PostMapping("/{dropId}/rate")
    public Rate rate(@PathVariable String dropId, @RequestBody SetRate request) {
        if (request == null || request.admitRate() == null
                || !OFFERED_RATES.contains(request.admitRate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "admitRate must be one of " + OFFERED_RATES);
        }
        int admitRate = request.admitRate();
        try {
            gate.post()
                    .uri("/internal/drops/{id}/rate", dropId)
                    .body(Map.of("admitRate", admitRate))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // The gate's own refusal, kept as it was written: 404 for a drop
            // that expired, 409 for the canonical one. Flattening either into a
            // 503 would tell the person pressing the button that the system was
            // broken when in fact it had answered them precisely.
            throw new ResponseStatusException(HttpStatus.valueOf(e.getStatusCode().value()),
                    reasonFrom(e, dropId));
        } catch (Exception e) {
            throw unavailable("queue-gate", e);
        }
        log.info("Set drop {} to {} admissions/s", dropId, admitRate);
        return new Rate(dropId, admitRate);
    }

    private String reasonFrom(RestClientResponseException e, String dropId) {
        if (e.getStatusCode().value() == 404) {
            return "no drop " + dropId + ": it may have expired";
        }
        String body = e.getResponseBodyAsString();
        return body == null || body.isBlank()
                ? "queue-gate refused the change: " + e.getStatusText()
                : body;
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

    /** {@code {"admitRate": 200}}; one of 1, 8 or 200. */
    public record SetRate(Integer admitRate) { }

    /** What the rate is now, so the page does not have to assume it took. */
    public record Rate(String dropId, int admitRate) { }

    /** What the browser needs to watch what it just made. */
    public record Sandbox(String dropId, long slotId, int admitRate) { }

    /** The gate returns the whole DropRecord; this is the part the console uses. */
    public record CreatedDrop(String id, Long slotId) { }
}
