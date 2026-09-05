package dev.marwan.gate.queue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic the dashboard shows.
 *
 * QueueStateProvider says a dashboard where issued - admitted != waiting
 * "undermines exactly the numbers this project asks people to trust". These are
 * the tests that make that true rather than merely stated.
 */
class QueueStateTest {

    /**
     * Read from the deployed console: 17 tickets ever issued, 79,690 admitted.
     *
     * admittedBy() is elapsed time times the rate and never stops climbing, so
     * a drop left open overnight reports a number with no relation to anyone.
     * It is the right function for deciding whether ticket N may pass - N is
     * compared against it - and the wrong number to show a person, because
     * nobody admitted seventy-nine thousand people through a door seventeen
     * walked through. It also made the page look alive while every honest
     * counter sat at zero, which is worse than looking idle.
     */
    @Test
    void admittedNeverExceedsTheTicketsActuallyIssued() {
        QueueState state = QueueState.of(17, 79_690, 5000);

        assertThat(state.admitted()).isEqualTo(17);
        assertThat(state.waiting()).isZero();
    }

    /** The invariant the provider's own comment depends on, at every scale. */
    @Test
    void issuedMinusAdmittedIsAlwaysWaiting() {
        long[][] cases = {
                {0, 0}, {17, 79_690}, {5000, 12}, {250, 250}, {1, 0}, {3000, 2999}
        };
        for (long[] c : cases) {
            QueueState state = QueueState.of(c[0], c[1], 5000);
            assertThat(state.ticketsIssued() - state.admitted())
                    .as("issued %d, admitted %d", c[0], c[1])
                    .isEqualTo(state.waiting());
        }
    }

    /** Before the rush reaches them, everyone who joined is still waiting. */
    @Test
    void anUnservedQueueIsEveryoneWhoJoined() {
        QueueState state = QueueState.of(3000, 8, 5000);

        assertThat(state.admitted()).isEqualTo(8);
        assertThat(state.waiting()).isEqualTo(2992);
    }
}
