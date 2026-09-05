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
 * Every call here is behind the console key; see KeyFilter. With an audience
 * of two there is no reason to leave a read open, and one rule is easier to
 * reason about than two.
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
     *             what the public page shows. There is no slot parameter: the
     *             gate's drop state names the slot the drop sells, so the
     *             browser cannot ask for one drop's queue beside another
     *             drop's seats.
     */
    @GetMapping("/api/state")
    public ConsoleView state(@RequestParam(name = "drop", required = false) String drop) {
        String dropId = drop == null || drop.isBlank() ? properties.canonicalDrop() : drop;
        return new ConsoleView(state.currentFor(dropId), pods.current());
    }
}
