package dev.marwan.booking.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ChaosStateTest {

    static class MovingClock extends Clock {
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static class FakePool implements ChaosState.PoolSizer {
        int size = 5;
        @Override public int size() { return size; }
        @Override public void resize(int n) { size = n; }
    }

    final MovingClock clock = new MovingClock();
    final FakePool pool = new FakePool();
    final ChaosState chaos = new ChaosState(pool, clock);

    @Test
    void aSlowDatabaseDelaysBookingsUntilItExpiresByItself() {
        chaos.start("slow-database", 120);
        assertThat(chaos.delayMillis()).isEqualTo(ChaosState.SLOW_MILLIS);

        clock.advance(Duration.ofSeconds(119));
        chaos.revertIfExpired();
        assertThat(chaos.delayMillis()).isEqualTo(ChaosState.SLOW_MILLIS);

        clock.advance(Duration.ofSeconds(2));
        chaos.revertIfExpired();
        assertThat(chaos.delayMillis()).isZero();
        assertThat(chaos.current()).isEmpty();
    }

    @Test
    void aSqueezedPoolIsRestoredToItsOwnSizeWhenItExpires() {
        chaos.start("squeeze-pool", 60);
        assertThat(pool.size).isEqualTo(1);
        clock.advance(Duration.ofSeconds(61));
        chaos.revertIfExpired();
        assertThat(pool.size).isEqualTo(5);
    }

    @Test
    void secondsAreCappedAtTwoMinutes() {
        var active = chaos.start("slow-database", 999);
        assertThat(active.until()).isEqualTo(clock.now.plusSeconds(ChaosState.MAX_SECONDS));
    }

    @Test
    void anUnknownFaultIsRefused() {
        assertThatThrownBy(() -> chaos.start("delete-everything", 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewFaultReplacesTheOldOneAndRestoresThePoolFirst() {
        chaos.start("squeeze-pool", 60);
        chaos.start("slow-database", 60);
        assertThat(pool.size).isEqualTo(5);
        assertThat(chaos.current()).get().extracting(ChaosState.Active::fault).isEqualTo("slow-database");
    }

    @Test
    void clearingEndsTheFaultAndRestoresThePool() {
        chaos.start("squeeze-pool", 60);
        chaos.clear();
        assertThat(pool.size).isEqualTo(5);
        assertThat(chaos.current()).isEmpty();
    }
}
