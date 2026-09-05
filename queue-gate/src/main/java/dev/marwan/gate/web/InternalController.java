package dev.marwan.gate.web;

import dev.marwan.gate.config.DropProperties;
import dev.marwan.gate.queue.DropRecord;
import dev.marwan.gate.queue.DropRegistry;
import dev.marwan.gate.queue.QueueState;
import dev.marwan.gate.queue.QueueStateProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the demo console reads to draw a drop, and writes to start one.
 *
 * Reads go through QueueStateProvider rather than Redis directly, so the
 * console, the Prometheus gauges and the alert rules all report one computation
 * of issued, admitted and waiting. Phase 6 built that provider for exactly this
 * reason, and it is memoised per drop so two visitors read in quick succession
 * cannot be handed each other's queue depth.
 */
@RestController
@RequestMapping("/internal/drops")
public class InternalController {

    private final QueueStateProvider provider;
    private final DropRegistry drops;
    private final DropProperties properties;

    public InternalController(QueueStateProvider provider, DropRegistry drops,
                              DropProperties properties) {
        this.provider = provider;
        this.drops = drops;
        this.properties = properties;
    }

    /**
     * 404 for a drop that has expired rather than zeroes: a console showing an
     * empty queue for a sandbox that is gone would look like a working drop
     * nobody had joined.
     */
    @GetMapping("/{dropId}/state")
    public ResponseEntity<DropState> state(@PathVariable String dropId) {
        return drops.find(dropId)
                .map(drop -> ResponseEntity.ok(DropState.of(drop, provider.currentFor(dropId))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * A drop of someone's own, opening immediately.
     *
     * The gate is the only thing that knows what drops exist, so creating one is
     * a write here rather than a redeploy or a Redis command run by hand.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DropRecord create(@RequestBody(required = false) CreateDrop request) {
        CreateDrop asked = request == null ? new CreateDrop(null, null) : request;
        int admitRate = asked.admitRate() == null ? properties.admitRate() : asked.admitRate();
        if (admitRate <= 0) {
            // 400 rather than a stored drop that admits nobody: a rate of zero
            // would leave a visitor watching a queue that never moves with
            // nothing on screen to say why.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "admitRate must be positive but was " + admitRate);
        }
        return drops.create(admitRate, asked.slotId());
    }

    /** {@code {"admitRate": 8, "slotId": 4242}}; both optional. */
    public record CreateDrop(Integer admitRate, Long slotId) { }

    /**
     * The queue numbers, plus the slot the drop sells.
     *
     * Declared here rather than by widening {@link QueueState} because that
     * record is what Phase 6's Micrometer gauges read through
     * QueueStateProvider, and a slot id has no meaning to a gauge. The console
     * needs it for a different reason: without it a sandbox drop would be drawn
     * with the canonical restaurant's seats, which is the wrong number rendered
     * confidently.
     */
    public record DropState(
            String dropId,
            Long slotId,
            long ticketsIssued,
            long admitted,
            long waiting,
            int ticketCap) {

        static DropState of(DropRecord drop, QueueState queue) {
            return new DropState(drop.id(), drop.slotId(),
                    queue.ticketsIssued(), queue.admitted(), queue.waiting(), queue.ticketCap());
        }
    }
}
