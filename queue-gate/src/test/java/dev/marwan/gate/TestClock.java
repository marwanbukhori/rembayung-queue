package dev.marwan.gate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A Clock whose instant can be set and advanced, so time-dependent behaviour is deterministic. */
public class TestClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public TestClock(Instant now) {
        this(now, ZoneId.of("UTC"));
    }

    private TestClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void setNow(Instant instant) { this.now = instant; }

    public void advance(Duration duration) { this.now = this.now.plus(duration); }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId z) { return new TestClock(now, z); }

    @Override public Instant instant() { return now; }
}
