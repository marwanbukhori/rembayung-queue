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

        assertThatThrownBy(queueService::join)
                .isInstanceOf(DropNotOpenException.class)
                .satisfies(e -> assertThat(((DropNotOpenException) e).getSecondsUntilOpen())
                        .isEqualTo(120));
    }

    @Test
    void joiningAfterTheDropClosesIsRejected() {
        clock().setNow(OPENS_AT.plus(Duration.ofMinutes(31)));

        assertThatThrownBy(queueService::join).isInstanceOf(DropClosedException.class);
    }

    @Test
    void ticketsAreIssuedInOrderFromOne() {
        JoinResult first = queueService.join();
        JoinResult second = queueService.join();

        assertThat(first.ticket()).isEqualTo(1);
        assertThat(second.ticket()).isEqualTo(2);
        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void positionAndEtaReflectHowFarAdmissionHasAdvanced() {
        for (int i = 0; i < 399; i++) {
            queueService.join();
        }
        clock().advance(Duration.ofSeconds(1));   // 200 admitted

        JoinResult result = queueService.join();  // ticket 400

        assertThat(result.ticket()).isEqualTo(400);
        assertThat(result.position()).isEqualTo(200);
        assertThat(result.etaSeconds()).isEqualTo(1.0);
        assertThat(result.admitted()).isFalse();
    }

    @Test
    void anAlreadyAdmittedTicketReportsPositionZero() {
        clock().advance(Duration.ofSeconds(1));   // 200 admitted before anyone joins

        JoinResult result = queueService.join();  // ticket 1

        assertThat(result.position()).isZero();
        assertThat(result.admitted()).isTrue();
    }

    @Test
    void ticketsBeyondTheCapAreSoldOut() {
        for (int i = 0; i < 250; i++) {
            queueService.join();
        }

        assertThatThrownBy(queueService::join).isInstanceOf(SoldOutException.class);
    }

    @Test
    void theTicketIsStoredInRedisUnderTheToken() {
        JoinResult result = queueService.join();

        assertThat(redis.opsForValue().get("admit:" + result.token()))
                .isEqualTo(String.valueOf(result.ticket()));
    }
}
