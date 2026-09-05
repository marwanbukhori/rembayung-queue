package dev.marwan.gate.queue;

import dev.marwan.gate.RedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DropRegistryTest extends RedisTestBase {

    @Autowired private DropRegistry registry;

    @Test
    void theDefaultDropAlwaysExistsAndComesFromConfiguration() {
        DropRecord d = registry.defaultDrop();

        assertThat(d.id()).isEqualTo(DropRegistry.DEFAULT_ID);
        assertThat(d.ticketCap()).isPositive();
        assertThat(d.admitRate()).isPositive();
    }

    // A visitor's drop must open immediately — nobody waits for 21:00 to see a
    // demo — and must be independent of the canonical one.
    @Test
    void aCreatedDropOpensImmediatelyAndIsIndependent() {
        DropRecord created = registry.create(8, 4242L);

        assertThat(created.id()).isNotEqualTo(DropRegistry.DEFAULT_ID);
        assertThat(created.opensAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(created.admitRate()).isEqualTo(8);
        assertThat(created.slotId()).isEqualTo(4242L);

        assertThat(registry.find(created.id())).contains(created);
        assertThat(registry.defaultDrop().slotId())
                .isNotEqualTo(created.slotId());
    }

    @Test
    void twoCreatedDropsDoNotShareIdentity() {
        DropRecord a = registry.create(1, 1L);
        DropRecord b = registry.create(1, 2L);

        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    void anUnknownDropIsEmptyRatherThanAnError() {
        assertThat(registry.find("d-does-not-exist")).isEmpty();
    }
}
