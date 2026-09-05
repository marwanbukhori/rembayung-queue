package dev.marwan.gate.web;

import dev.marwan.gate.queue.DropRegistry;
import dev.marwan.gate.queue.QueueState;
import dev.marwan.gate.queue.QueueStateProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the demo console reads to draw a drop.
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

    public InternalController(QueueStateProvider provider, DropRegistry drops) {
        this.provider = provider;
        this.drops = drops;
    }

    /**
     * 404 for a drop that has expired rather than zeroes: a console showing an
     * empty queue for a sandbox that is gone would look like a working drop
     * nobody had joined.
     */
    @GetMapping("/{dropId}/state")
    public ResponseEntity<QueueState> state(@PathVariable String dropId) {
        return drops.find(dropId)
                .map(drop -> ResponseEntity.ok(provider.currentFor(dropId)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
