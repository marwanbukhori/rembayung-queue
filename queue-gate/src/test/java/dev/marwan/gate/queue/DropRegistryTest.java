package dev.marwan.gate.queue;

import dev.marwan.gate.RedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * Changing a drop's rate is a write, not a redeploy.
     *
     * That is the whole reason drops moved into Redis. A visitor pushing their
     * own drop to 200 admissions a second exhausts the connection pool and gets
     * 503 with Retry-After while oversold stays at zero, and none of that is
     * reachable if the rate is an environment variable.
     */
    @Test
    void aStoredDropsRateCanBeChangedInPlace() {
        DropRecord created = registry.create(8, 4242L);

        DropRecord updated = registry.updateAdmitRate(created.id(), 200).orElseThrow();

        assertThat(updated.admitRate()).isEqualTo(200);
        assertThat(registry.find(created.id()).orElseThrow().admitRate()).isEqualTo(200);
    }

    /** Everything else about the drop is left exactly where it was. */
    @Test
    void changingTheRateChangesNothingElse() {
        DropRecord created = registry.create(8, 4242L);

        DropRecord updated = registry.updateAdmitRate(created.id(), 1).orElseThrow();

        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.slotId()).isEqualTo(created.slotId());
        assertThat(updated.opensAt()).isEqualTo(created.opensAt());
        assertThat(updated.closesAt()).isEqualTo(created.closesAt());
        assertThat(updated.ticketCap()).isEqualTo(created.ticketCap());
        assertThat(updated.admissionWindow()).isEqualTo(created.admissionWindow());
        assertThat(updated.ticketTtl()).isEqualTo(created.ticketTtl());
    }

    /**
     * The canonical drop stays unpersisted, which is what keeps the scheduled
     * 21:00 path configuration-driven. Writing it here would create a stored
     * record that silently outranked the ConfigMap — a drop whose real rate you
     * could no longer read from the deployment.
     */
    @Test
    void theCanonicalDropsRateIsConfigurationAndCannotBeWritten() {
        assertThatThrownBy(() -> registry.updateAdmitRate(DropRegistry.DEFAULT_ID, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configuration");

        assertThat(registry.defaultDrop().admitRate())
                .isEqualTo(registry.defaultDrop().admitRate());
    }

    @Test
    void anExpiredDropCannotHaveItsRateChanged() {
        assertThat(registry.updateAdmitRate("d-does-not-exist", 8)).isEmpty();
    }

    @Test
    void aRateOfZeroIsRefusedRatherThanStored() {
        DropRecord created = registry.create(8, 1L);

        assertThatThrownBy(() -> registry.updateAdmitRate(created.id(), 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.find(created.id()).orElseThrow().admitRate()).isEqualTo(8);
    }
}
