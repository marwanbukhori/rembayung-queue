package dev.marwan.console.web;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.cluster.PodHealthProvider;
import dev.marwan.console.state.DemoStateProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint the page polls.
 *
 * It answers 200 whatever the services underneath are doing. A console that
 * returns 500 when a dependency blinks becomes the second thing broken during
 * an incident, and this page is what someone opens precisely when the system is
 * unhappy — so an unreachable service is reported as a reason inside a
 * successful response, never as a failure of the console itself.
 *
 * Tasks 6 and 7 extend this controller with drop creation and load runs.
 */
@RestController
public class StateController {

    private final DemoStateProvider state;
    private final PodHealthProvider pods;
    private final ConsoleProperties properties;

    public StateController(DemoStateProvider state, PodHealthProvider pods,
                           ConsoleProperties properties) {
        this.state = state;
        this.pods = pods;
        this.properties = properties;
    }

    /**
     * @param drop which drop to read; the canonical one when unasked, which is
     *             what the public page shows
     * @param slot which slot that drop sells. Passed rather than looked up
     *             because the gate's drop state endpoint returns queue numbers
     *             only. Task 6 hands the browser the slot id when it creates a
     *             sandbox, and until then the canonical slot is the answer.
     */
    @GetMapping("/api/state")
    public ConsoleView state(
            @RequestParam(name = "drop", required = false) String drop,
            @RequestParam(name = "slot", required = false) Long slot) {
        String dropId = drop == null || drop.isBlank() ? properties.canonicalDrop() : drop;
        long slotId = slot == null ? properties.canonicalSlot() : slot;
        return new ConsoleView(state.currentFor(dropId, slotId), pods.current());
    }
}
