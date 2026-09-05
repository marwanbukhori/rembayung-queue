package dev.marwan.console.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DemoStateTest {

    // A service being unreachable is normal — pods restart, drops expire — and
    // must render as "unknown" rather than throwing. A console that 500s when a
    // dependency blinks is worse than one that says it cannot see right now.
    @Test
    void anUnreachableServiceBecomesUnknownRatherThanAnError() {
        DemoState state = DemoState.unavailable("queue-gate did not answer");

        assertThat(state.available()).isFalse();
        assertThat(state.detail()).contains("queue-gate");
    }

    @Test
    void aCompleteStateReportsSeatsAndQueueTogether() {
        DemoState state = new DemoState(true, null, "d-abc", 4242,
                250, 202, 48, 0, 40, 10, 30);

        assertThat(state.slotId()).isEqualTo(4242);
        assertThat(state.capacity()).isEqualTo(250);
        assertThat(state.oversold()).isZero();
        assertThat(state.waiting()).isEqualTo(30);
    }
}
