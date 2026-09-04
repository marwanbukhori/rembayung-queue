package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueStateProviderTest {

    private static final Instant OPENS = Instant.parse("2026-10-01T13:00:00Z");

    @SuppressWarnings("unchecked")
    private QueueStateProvider providerWith(String counterValue, Instant now, int admitRate) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(counterValue);

        DropProperties drop = new DropProperties(
                OPENS, OPENS.plus(Duration.ofHours(1)), 250, 250, admitRate,
                Duration.ofMinutes(5), Duration.ofMinutes(30));

        return new QueueStateProvider(redis, drop, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void reportsWaitingAsIssuedMinusAdmitted() {
        // 10s after opening at 1/second, 10 are admitted of 40 issued.
        QueueState state = providerWith("40", OPENS.plusSeconds(10), 1).current();

        assertThat(state.ticketsIssued()).isEqualTo(40);
        assertThat(state.admitted()).isEqualTo(10);
        assertThat(state.waiting()).isEqualTo(30);
    }

    // An untouched Redis has no counter key at all. That is zero issued, not an
    // error and not a NullPointerException on a metrics scrape.
    @Test
    void treatsAnAbsentCounterAsZeroIssued() {
        QueueState state = providerWith(null, OPENS.plusSeconds(10), 1).current();

        assertThat(state.ticketsIssued()).isZero();
        assertThat(state.waiting()).isZero();
    }

    // Admission is a pure function of time and keeps counting past the last
    // issued ticket. Waiting must not go negative, which would render as a
    // nonsense queue depth on a dashboard.
    @Test
    void neverReportsNegativeWaiting() {
        QueueState state = providerWith("5", OPENS.plusSeconds(600), 1).current();

        assertThat(state.admitted()).isGreaterThan(state.ticketsIssued());
        assertThat(state.waiting()).isZero();
    }

    // The three gauges each call current() independently. Without memoisation
    // they take three separate Redis reads and three separate clock reads, so a
    // scrape straddling a second boundary can publish an `admitted` that does
    // not match the `admitted` used to derive `waiting`. Consecutive reads at
    // the same instant must return the identical snapshot.
    @Test
    void returnsOneConsistentSnapshotAcrossRepeatedReadsAtTheSameInstant() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("40");

        DropProperties drop = new DropProperties(
                OPENS, OPENS.plus(Duration.ofHours(1)), 250, 250, 1,
                Duration.ofMinutes(5), Duration.ofMinutes(30));
        QueueStateProvider provider = new QueueStateProvider(
                redis, drop, Clock.fixed(OPENS.plusSeconds(10), ZoneOffset.UTC));

        QueueState a = provider.current();
        QueueState b = provider.current();
        QueueState c = provider.current();

        assertThat(a).isEqualTo(b).isEqualTo(c);
        // and the derived field genuinely agrees with the two it is derived from
        assertThat(a.waiting()).isEqualTo(a.ticketsIssued() - a.admitted());
        verify(ops, times(1)).get(anyString());
    }
}
