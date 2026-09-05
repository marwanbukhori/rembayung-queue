package dev.marwan.gate;

import dev.marwan.gate.queue.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueServiceTest extends RedisTestBase {

    @Autowired private QueueService queueService;

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

    @Test
    void anAlreadyAdmittedTicketReportsPositionZero() {
        clock().advance(Duration.ofSeconds(1));   // 200 admitted before anyone joins

        JoinResult result = queueService.join(DropRegistry.DEFAULT_ID);  // ticket 1

        assertThat(result.position()).isZero();
        assertThat(result.admitted()).isTrue();
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
