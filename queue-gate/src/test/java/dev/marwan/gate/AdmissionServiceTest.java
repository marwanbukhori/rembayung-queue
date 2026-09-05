package dev.marwan.gate;

import dev.marwan.gate.queue.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionServiceTest extends RedisTestBase {

    @Autowired private QueueService queueService;
    @Autowired private AdmissionService admissionService;

    @Test
    void anUnknownTokenHasNoPosition() {
        assertThat(admissionService.position("no-such-token")).isEmpty();
    }

    @Test
    void positionShrinksAsAdmissionAdvances() {
        for (int i = 0; i < 149; i++) {
            queueService.join(DropRegistry.DEFAULT_ID);
        }
        JoinResult mine = queueService.join(DropRegistry.DEFAULT_ID);   // ticket 150

        Optional<PositionView> before = admissionService.position(mine.token());
        assertThat(before).isPresent();
        assertThat(before.get().position()).isEqualTo(150);
        assertThat(before.get().admitted()).isFalse();

        clock().advance(Duration.ofMillis(500));  // 100 admitted at 200/s

        PositionView after = admissionService.position(mine.token()).orElseThrow();
        assertThat(after.position()).isEqualTo(50);
        assertThat(after.admitted()).isFalse();
    }

    @Test
    void anAdmittedTokenReportsItsRemainingWindow() {
        JoinResult mine = queueService.join(DropRegistry.DEFAULT_ID);   // ticket 1
        clock().advance(Duration.ofSeconds(1));

        PositionView view = admissionService.position(mine.token()).orElseThrow();

        assertThat(view.position()).isZero();
        assertThat(view.admitted()).isTrue();
        assertThat(view.expiresInSeconds()).isBetween(290L, 300L);
    }

    @Test
    void consumingAnAdmittedTokenSucceedsExactlyOnce() {
        JoinResult mine = queueService.join(DropRegistry.DEFAULT_ID);
        clock().advance(Duration.ofSeconds(1));

        admissionService.consume(mine.token());

        assertThatThrownBy(() -> admissionService.consume(mine.token()))
                .isInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).getReason())
                        .isEqualTo("TOKEN_INVALID"));
    }

    @Test
    void aTokenWhoseTurnHasNotComeIsRejected() {
        for (int i = 0; i < 149; i++) {
            queueService.join(DropRegistry.DEFAULT_ID);
        }
        JoinResult mine = queueService.join(DropRegistry.DEFAULT_ID);   // ticket 150, not yet admitted

        assertThatThrownBy(() -> admissionService.consume(mine.token()))
                .isInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).getReason())
                        .isEqualTo("TOKEN_NOT_YET_ADMITTED"));
    }

    @Test
    void aTokenPastItsFiveMinuteWindowIsRejected() {
        JoinResult mine = queueService.join(DropRegistry.DEFAULT_ID);
        clock().advance(Duration.ofMinutes(6));

        assertThatThrownBy(() -> admissionService.consume(mine.token()))
                .isInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).getReason())
                        .isEqualTo("TOKEN_EXPIRED"));
    }

    @Test
    void anUnknownTokenIsRejected() {
        assertThatThrownBy(() -> admissionService.consume("no-such-token"))
                .isInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).getReason())
                        .isEqualTo("TOKEN_INVALID"));
    }
}
