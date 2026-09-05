package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new QueueStateProvider(redis, new DropRegistry(redis, drop, clock), clock);
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
    // issued ticket, so a drop left open drifts arbitrarily far ahead of anyone
    // who actually joined.
    //
    // This test used to assert admitted() > ticketsIssued() - it required the
    // reported figure to be the runaway one, and so protected the bug rather
    // than the behaviour. The deployed console read 17 issued against 79,690
    // admitted, and that figure was the only thing on the page still moving
    // while every honest counter sat at zero.
    @Test
    void reportsNoMoreAdmittedThanEverJoined() {
        QueueState state = providerWith("5", OPENS.plusSeconds(600), 1).current();

        assertThat(state.ticketsIssued()).isEqualTo(5);
        assertThat(state.admitted()).isEqualTo(5);
        assertThat(state.waiting()).isZero();
    }

    // The three gauges each call current() independently. Without memoisation
    // they take three separate Redis reads and three separate clock reads, so a
    // scrape straddling a second boundary can publish an `admitted` that does
    // not match the `admitted` used to derive `waiting`. Consecutive reads at
    // the same instant must return the identical snapshot.
    @Test
    @SuppressWarnings("unchecked")
    void returnsOneConsistentSnapshotAcrossRepeatedReadsAtTheSameInstant() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("40");

        DropProperties drop = new DropProperties(
                OPENS, OPENS.plus(Duration.ofHours(1)), 250, 250, 1,
                Duration.ofMinutes(5), Duration.ofMinutes(30));
        Clock clock = Clock.fixed(OPENS.plusSeconds(10), ZoneOffset.UTC);
        QueueStateProvider provider = new QueueStateProvider(
                redis, new DropRegistry(redis, drop, clock), clock);

        QueueState a = provider.current();
        QueueState b = provider.current();
        QueueState c = provider.current();

        assertThat(a).isEqualTo(b).isEqualTo(c);
        // and the derived field genuinely agrees with the two it is derived from
        assertThat(a.waiting()).isEqualTo(a.ticketsIssued() - a.admitted());
        verify(ops, times(1)).get(anyString());
    }

    // The memo above is keyed by drop, not global. A single-field cache would
    // hand the second caller the first drop's numbers for the whole 500ms TTL,
    // which on the console is one visitor being shown another visitor's queue
    // depth — a wrong answer given confidently, not a stale one.
    @Test
    @SuppressWarnings("unchecked")
    void memoisesEachDropSeparately() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(QueueService.ticketCounter("d-abc"))).thenReturn("40");
        when(ops.get(QueueService.ticketCounter("d-xyz"))).thenReturn("7");

        DropRegistry drops = mock(DropRegistry.class);
        when(drops.find("d-abc")).thenReturn(Optional.of(drop("d-abc")));
        when(drops.find("d-xyz")).thenReturn(Optional.of(drop("d-xyz")));

        QueueStateProvider provider = new QueueStateProvider(redis, drops,
                Clock.fixed(OPENS.plusSeconds(10), ZoneOffset.UTC));

        // Both reads land inside one TTL window: the clock is fixed.
        QueueState abc = provider.currentFor("d-abc");
        QueueState xyz = provider.currentFor("d-xyz");

        assertThat(abc.ticketsIssued()).isEqualTo(40);
        assertThat(abc.waiting()).isEqualTo(30);
        assertThat(xyz.ticketsIssued()).isEqualTo(7);
        assertThat(xyz.waiting()).isZero();

        // and each drop is still memoised in its own right
        assertThat(provider.currentFor("d-abc")).isEqualTo(abc);
        assertThat(provider.currentFor("d-xyz")).isEqualTo(xyz);
        verify(ops, times(1)).get(QueueService.ticketCounter("d-abc"));
        verify(ops, times(1)).get(QueueService.ticketCounter("d-xyz"));
    }

    // current() is what the three Phase 6 gauges call. It must keep meaning
    // "the canonical 21:00 drop" and not whichever sandbox was read last.
    @Test
    @SuppressWarnings("unchecked")
    void currentStillReportsTheDefaultDrop() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(QueueService.TICKET_COUNTER)).thenReturn("40");
        when(ops.get(QueueService.ticketCounter("d-abc"))).thenReturn("7");

        DropProperties properties = new DropProperties(
                OPENS, OPENS.plus(Duration.ofHours(1)), 250, 250, 1,
                Duration.ofMinutes(5), Duration.ofMinutes(30));
        Clock clock = Clock.fixed(OPENS.plusSeconds(10), ZoneOffset.UTC);
        DropRegistry drops = mock(DropRegistry.class);
        when(drops.find(DropRegistry.DEFAULT_ID))
                .thenReturn(Optional.of(new DropRegistry(redis, properties, clock).defaultDrop()));
        when(drops.find("d-abc")).thenReturn(Optional.of(drop("d-abc")));

        QueueStateProvider provider = new QueueStateProvider(redis, drops, clock);

        provider.currentFor("d-abc");
        assertThat(provider.current().ticketsIssued()).isEqualTo(40);
        assertThat(provider.current()).isEqualTo(provider.currentFor(DropRegistry.DEFAULT_ID));
    }

    @Test
    void readingAnUnknownDropIsRejectedRatherThanReportedAsEmpty() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DropRegistry drops = mock(DropRegistry.class);
        when(drops.find("d-gone")).thenReturn(Optional.empty());

        QueueStateProvider provider = new QueueStateProvider(
                redis, drops, Clock.fixed(OPENS, ZoneOffset.UTC));

        assertThatThrownBy(() -> provider.currentFor("d-gone"))
                .isInstanceOf(UnknownDropException.class);
    }

    private static DropRecord drop(String id) {
        return new DropRecord(id, OPENS, OPENS.plus(Duration.ofHours(1)), 250, 1,
                Duration.ofMinutes(5), Duration.ofMinutes(30), 42L);
    }
}
