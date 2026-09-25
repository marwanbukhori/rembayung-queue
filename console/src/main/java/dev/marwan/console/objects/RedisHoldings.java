package dev.marwan.console.objects;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.state.DemoState;
import dev.marwan.console.state.DemoStateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * What redis holds, for its Deployment view. redis logs only its lifecycle,
 * and the console cannot reach it - the NetworkPolicy admits queue-gate only -
 * so this is read from the gate's own account of the drop.
 */
@FunctionalInterface
public interface RedisHoldings {

    List<ObjectDetail.Fact> facts();

    @Component
    class FromDropState implements RedisHoldings {

        private final DemoStateProvider drops;
        private final ConsoleProperties properties;

        FromDropState(DemoStateProvider drops, ConsoleProperties properties) {
            this.drops = drops;
            this.properties = properties;
        }

        @Override
        public List<ObjectDetail.Fact> facts() {
            DemoState state = drops.currentFor(properties.canonicalDrop());
            if (!state.available()) {
                return List.of(new ObjectDetail.Fact("Holds", "not readable: " + state.detail(), ObjectDetail.WARN));
            }
            return List.of(
                    new ObjectDetail.Fact("Holds", state.ticketsIssued() + " tickets issued, "
                            + state.admitted() + " admitted, " + state.waiting() + " waiting"),
                    new ObjectDetail.Fact("Read from", "queue-gate's drop state; the console cannot reach redis"));
        }
    }
}
