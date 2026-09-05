package dev.marwan.gate;

import dev.marwan.gate.queue.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueServiceTest extends RedisTestBase {

    @Autowired private QueueService queueService;
    @Autowired private AdmissionService admissionService;

    @Test
    void joiningBeforeTheDropOpensIsRejectedWithACountdown() {
        clock().setNow(OPENS_AT.minusSeconds(120));

        assertThatThrownBy(() -> queueService.join(DropRegistry.DEFAULT_ID))
                .isInstanceOf(DropNotOpenException.class)
                .satisfies(e -> assertThat(((DropNotOpenException) e).getSecondsUntilOpen())
                        .isEqualTo(120));
    }

    @Test
    void joiningAfterTheDropClosesIsRejected() {
        clock().setNow(OPENS_AT.plus(Duration.ofMinutes(31)));

        assertThatThrownBy(() -> queueService.join(DropRegistry.DEFAULT_ID)).isInstanceOf(DropClosedException.class);
    }

    @Test
    void ticketsAreIssuedInOrderFromOne() {
        JoinResult first = queueService.join(DropRegistry.DEFAULT_ID);
        JoinResult second = queueService.join(DropRegistry.DEFAULT_ID);

        assertThat(first.ticket()).isEqualTo(1);
        assertThat(second.ticket()).isEqualTo(2);
        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void positionAndEtaReflectHowFarAdmissionHasAdvanced() {
        for (int i = 0; i < 149; i++) {
            queueService.join(DropRegistry.DEFAULT_ID);
        }
        clock().advance(Duration.ofMillis(500));   // 100 admitted at 200/s

        JoinResult result = queueService.join(DropRegistry.DEFAULT_ID);   // ticket 150

        assertThat(result.ticket()).isEqualTo(150);
        assertThat(result.position()).isEqualTo(50);
        assertThat(result.etaSeconds()).isEqualTo(0.25);
        assertThat(result.admitted()).isFalse();
    }

    /**
     * The clock starts with the crowd, so nothing is admitted before anyone is
     * there to admit.
     *
     * This test used to advance a second first and assert the first joiner was
     * already through - "200 admitted before anyone joins". That banking is the
     * bug: on the deployed console a sandbox drop sat open for the forty seconds
     * its load job took to schedule, banked three hundred and twenty places, and
     * then admitted all two hundred arrivals on contact. issued 200, admitted
     * 200, waiting 0, for the entire run. There was never a queue to look at.
     *
     * The first arrival now starts the admission clock and is through on the
     * first tick of it, which at 200 a second is five milliseconds later.
     */
    @Test
    void theFirstArrivalStartsTheClockAndIsAdmittedOnTheFirstTick() {
        clock().advance(Duration.ofSeconds(1));   // an open gate nobody has used

        JoinResult joined = queueService.join(DropRegistry.DEFAULT_ID);  // ticket 1
        assertThat(joined.ticket()).isEqualTo(1);
        assertThat(joined.admitted()).isFalse();

        clock().advance(Duration.ofMillis(5));    // one tick at 200 a second

        assertThat(admissionService.position(joined.token()).orElseThrow().admitted()).isTrue();
    }

    @Test
    void ticketsBeyondTheCapAreSoldOut() {
        for (int i = 0; i < 250; i++) {
            queueService.join(DropRegistry.DEFAULT_ID);
        }

        assertThatThrownBy(() -> queueService.join(DropRegistry.DEFAULT_ID)).isInstanceOf(SoldOutException.class);
    }

    // The stored value now carries the drop alongside the ticket, which is what
    // lets a token resolve its own drop and keeps GET /queue/{token} unchanged.
    @Test
    void theTicketIsStoredInRedisUnderTheToken() {
        JoinResult result = queueService.join(DropRegistry.DEFAULT_ID);

        assertThat(redis.opsForValue().get("admit:" + result.token()))
                .isEqualTo(DropRegistry.DEFAULT_ID + ":" + result.ticket());
    }
}
