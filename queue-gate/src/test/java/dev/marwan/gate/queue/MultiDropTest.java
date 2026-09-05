package dev.marwan.gate.queue;

import dev.marwan.gate.RedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MultiDropTest extends RedisTestBase {

    @Autowired private QueueService queueService;
    @Autowired private AdmissionService admissionService;
    @Autowired private DropRegistry registry;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;

    // The point of the whole task: two visitors must not share a ticket counter.
    @Test
    void twoDropsHaveIndependentTicketNumbering() {
        DropRecord a = registry.create(1, 100L);
        DropRecord b = registry.create(1, 200L);

        JoinResult a1 = queueService.join(a.id());
        JoinResult a2 = queueService.join(a.id());
        JoinResult b1 = queueService.join(b.id());

        assertThat(a1.ticket()).isEqualTo(1);
        assertThat(a2.ticket()).isEqualTo(2);
        // b is a different drop, so it starts at 1 rather than continuing at 3.
        assertThat(b1.ticket()).isEqualTo(1);
    }

    // The token has to be self-describing, or GET /queue/{token} would need a
    // drop parameter and every existing client would break.
    @Test
    void aTokenResolvesItsOwnDropWithoutBeingTold() {
        DropRecord drop = registry.create(1, 300L);
        JoinResult joined = queueService.join(drop.id());

        assertThat(admissionService.position(joined.token())).isPresent();
    }

    @Test
    void joiningAnUnknownDropIsRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> queueService.join("d-nope"))
                .isInstanceOf(UnknownDropException.class);
    }

    // A token whose drop has gone must be indistinguishable from a token that
    // never existed: same empty position, same TOKEN_INVALID on consume.
    @Test
    void aTokenWhoseDropHasExpiredBehavesLikeAnUnknownToken() {
        DropRecord drop = registry.create(1, 400L);
        JoinResult joined = queueService.join(drop.id());

        redis.delete("drop:" + drop.id());

        assertThat(admissionService.position(joined.token())).isEmpty();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> admissionService.consume(joined.token()))
                .isInstanceOf(TokenRejectedException.class)
                .extracting(e -> ((TokenRejectedException) e).getReason())
                .isEqualTo("TOKEN_INVALID");
    }

    // A rolling deploy replaces pods one at a time, so for a few minutes tokens
    // in the old encoding ("{ticket}") and the new one ("{dropId}:{ticket}") are
    // both live. Someone queueing when the rollout starts must not get a 403
    // because of it.
    @Test
    void aTokenIssuedBeforeDropsExistedStillResolves() {
        String legacyToken = "legacy-" + java.util.UUID.randomUUID();
        redis.opsForValue().set("admit:" + legacyToken, "1", java.time.Duration.ofMinutes(5));

        assertThat(admissionService.position(legacyToken)).isPresent();
    }
}
