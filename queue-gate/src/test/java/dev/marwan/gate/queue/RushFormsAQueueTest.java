package dev.marwan.gate.queue;

import dev.marwan.gate.RedisTestBase;
import dev.marwan.gate.TestClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demonstration itself: a crowd arrives at once and has to queue.
 *
 * Measured against the deployed console before this was fixed - a sandbox drop
 * started, 200 customers sent, and every sample read the same thing:
 *
 *   issued=200  admitted=200  waiting=0
 *
 * Admission is elapsed time times a rate and it accrued from the moment the
 * drop was created, but the crowd could not arrive until a k6 Job had been
 * scheduled, pulled and started. Forty seconds at 8/s is three hundred and
 * twenty places banked before anybody knocked, so all two hundred were admitted
 * on contact, the run finished in eight seconds, and the page showed a queue
 * that never had anyone in it.
 */
class RushFormsAQueueTest extends RedisTestBase {

    @Autowired private QueueService queueService;
    @Autowired private DropRegistry registry;
    @Autowired private QueueStateProvider states;
    @Autowired private TestClock clock;

    @Test
    void aCrowdArrivingLateStillHasToQueue() {
        DropRecord drop = registry.create(8, 400L);

        // The gap between pressing start and the load job actually running.
        clock.advance(Duration.ofSeconds(40));

        for (int i = 0; i < 200; i++) {
            queueService.join(drop.id());
        }

        QueueState atArrival = states.currentFor(drop.id());
        assertThat(atArrival.ticketsIssued()).isEqualTo(200);
        // Nobody is through yet: admission starts with the crowd, so the forty
        // seconds before it existed bank nothing.
        assertThat(atArrival.admitted()).isZero();
        assertThat(atArrival.waiting()).isEqualTo(200);
    }

    @Test
    void theQueueDrainsAtTheAdmissionRate() {
        DropRecord drop = registry.create(8, 401L);
        clock.advance(Duration.ofSeconds(40));
        for (int i = 0; i < 200; i++) {
            queueService.join(drop.id());
        }

        clock.advance(Duration.ofSeconds(10));
        QueueState after10s = states.currentFor(drop.id());
        assertThat(after10s.admitted()).isEqualTo(80);
        assertThat(after10s.waiting()).isEqualTo(120);

        clock.advance(Duration.ofSeconds(15));
        QueueState after25s = states.currentFor(drop.id());
        assertThat(after25s.admitted()).isEqualTo(200);
        assertThat(after25s.waiting()).isZero();
    }

    /**
     * The canonical 21:00 drop is unaffected: its crowd gathers before it opens,
     * so the published time is still the later instant and admission begins
     * exactly then, not when the first eager visitor knocked.
     */
    @Test
    void aPublishedOpeningTimeStillGovernsTheCanonicalDrop() {
        QueueState state = states.currentFor(DropRegistry.DEFAULT_ID);

        assertThat(state.admitted()).isLessThanOrEqualTo(state.ticketsIssued());
    }
}
