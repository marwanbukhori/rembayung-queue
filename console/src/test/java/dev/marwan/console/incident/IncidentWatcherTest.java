package dev.marwan.console.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.chaos.ChaosServiceTest;
import dev.marwan.console.slo.SloReading;

class IncidentWatcherTest {

    static class MovingClock extends Clock {
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        void advance(int seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    MovingClock clock;
    IncidentStore store;
    SloReading reading;
    Optional<ChaosService.ActiveFault> fault;
    List<String> pods;
    List<String> warnings;
    List<Incident> closed;

    SloReading healthy() { return new SloReading(clock.now, true, null, true, 1.0, 0.4); }
    SloReading breach() { return new SloReading(clock.now, true, null, true, 0.9, 3.0); }
    SloReading idle() { return new SloReading(clock.now, true, null, false, null, null); }

    @BeforeEach
    void setUp() {
        clock = new MovingClock();
        store = new IncidentStore(new ChaosServiceTest.Maps());
        reading = null;
        fault = Optional.empty();
        pods = new ArrayList<>(List.of("booking-service-a", "booking-service-b"));
        warnings = new ArrayList<>();
        closed = new ArrayList<>();
    }

    IncidentWatcher watcher() {
        return new IncidentWatcher(() -> reading, store, () -> fault, () -> pods, () -> warnings, clock, closed::add);
    }

    ChaosService.ActiveFault drill(int seconds) {
        return new ChaosService.ActiveFault("squeeze-pool", clock.now, clock.now.plusSeconds(seconds));
    }

    @Test
    void aDrillOpensAnIncidentAtOnce() {
        IncidentWatcher w = watcher();
        w.drillStarted(drill(120));
        Incident i = store.open().orElseThrow();
        assertThat(i.kind).isEqualTo("drill");
        assertThat(i.fault).isEqualTo("squeeze-pool");
        assertThat(i.timeline).anyMatch(e -> e.source().equals("chaos"));
    }

    @Test
    void aBriefBreachDoesNotOpenAnIncidentButASustainedOneDoes() {
        IncidentWatcher w = watcher();
        reading = breach();
        w.tick();
        clock.advance(15);
        w.tick();
        assertThat(store.open()).isEmpty();
        clock.advance(15);
        w.tick();
        assertThat(store.open()).get().extracting(i -> i.kind).isEqualTo("breach");
    }

    @Test
    void itResolvesAfterSixtySecondsHealthyAndHandsOverForThePostmortem() {
        IncidentWatcher w = watcher();
        w.drillStarted(drill(120));
        reading = breach();
        w.tick();
        reading = healthy();
        for (int s = 0; s <= 60; s += 15) {
            clock.advance(15);
            w.tick();
        }
        assertThat(store.open()).isEmpty();
        Incident i = store.list().get(0);
        assertThat(i.status).isEqualTo("resolved");
        assertThat(i.timeline).anyMatch(e -> e.source().equals("slo") && e.text().contains("recovered"));
        assertThat(closed).hasSize(1);
    }

    @Test
    void aFixThatHasNotWorkedAfterThreeMinutesIsRecordedOnce() {
        IncidentWatcher w = watcher();
        w.drillStarted(drill(120));
        reading = breach();
        String id = store.open().orElseThrow().id;
        w.update(id, i -> {
            i.proposals.add(new Incident.Proposal(1, clock.now, "restart-booking", "booking-service", null, "r",
                    List.of(), "approved", clock.now));
            i.status = "mitigating";
        });
        clock.advance(179);
        w.tick();
        assertThat(store.get(id).orElseThrow().timeline).noneMatch(e -> e.text().contains("not recovered"));
        clock.advance(15);
        w.tick();
        clock.advance(15);
        w.tick();
        Incident i = store.get(id).orElseThrow();
        assertThat(i.timeline).filteredOn(e -> e.text().contains("not recovered")).hasSize(1)
                .allMatch(e -> e.source().equals("slo"));
        assertThat(i.status).isEqualTo("open");
    }

    @Test
    void anIdleDrillResolvesOnceItsFaultHasEnded() {
        IncidentWatcher w = watcher();
        fault = Optional.of(drill(30));
        w.drillStarted(fault.get());
        reading = idle();
        w.tick();
        clock.advance(30);
        fault = Optional.empty();
        for (int s = 0; s <= 60; s += 15) {
            clock.advance(15);
            w.tick();
        }
        assertThat(store.open()).isEmpty();
    }

    /** Review I7: with Prometheus unreadable, a drill whose fault has ended must not stay open for ever. */
    @Test
    void aDrillWhoseSlosCannotBeReadClosesAsUnresolvedAfterTheFaultEnds() {
        IncidentWatcher w = watcher();
        fault = Optional.of(drill(30));
        w.drillStarted(fault.get());
        reading = new SloReading(clock.now, false, "Prometheus answered HTTP 503", false, null, null);
        w.tick();
        clock.advance(30);
        fault = Optional.empty();
        for (int s = 0; s <= 60; s += 15) {
            clock.advance(15);
            w.tick();
        }
        assertThat(store.open()).isEmpty();
        Incident closed = store.list().get(0);
        assertThat(closed.status).isEqualTo("unresolved");
        assertThat(closed.timeline).anyMatch(e -> e.text().contains("could not be read"));
    }

    /** Review I6: a watcher that fails must not stop temporary raises from being put back. */
    @Test
    void aFailingWatcherDoesNotBlockTheReverts() {
        IncidentWatcher failing = org.mockito.Mockito.mock(IncidentWatcher.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("events unreadable")).when(failing).tick();
        Remediation remediation = org.mockito.Mockito.mock(Remediation.class);
        new IncidentConfiguration.IncidentTicker(failing, remediation, true).tick();
        org.mockito.Mockito.verify(remediation).revertDue();
    }

    @Test
    void podChangesAndWarningsJoinTheTimelineOnce() {
        IncidentWatcher w = watcher();
        w.drillStarted(drill(120));
        reading = breach();
        w.tick();
        pods.remove("booking-service-b");
        warnings.add("BackOff on Pod/booking-service-a: back-off restarting");
        clock.advance(15);
        w.tick();
        pods.add("booking-service-c");
        clock.advance(15);
        w.tick();
        w.tick();
        Incident i = store.open().orElseThrow();
        assertThat(i.timeline).filteredOn(e -> e.source().equals("kubernetes")).extracting(IncidentEvent::text)
                .containsExactly("pod booking-service-b gone", "BackOff on Pod/booking-service-a: back-off restarting",
                        "pod booking-service-c created");
    }

    @Test
    void aRestartedConsoleCarriesOnWithTheOpenIncident() {
        watcher().drillStarted(drill(120));
        IncidentWatcher after = watcher();
        reading = healthy();
        for (int s = 0; s <= 60; s += 15) {
            clock.advance(15);
            after.tick();
        }
        assertThat(store.list()).hasSize(1);
        assertThat(store.list().get(0).status).isEqualTo("resolved");
    }

    @Test
    void theStoreKeepsTheNewestTwelve() {
        IncidentWatcher w = watcher();
        for (int n = 0; n < 14; n++) {
            w.drillStarted(drill(5));
            Incident open = store.open().orElseThrow();
            open.status = "resolved";
            store.put(open);
            clock.advance(60);
        }
        assertThat(store.list()).hasSize(12);
    }
}
