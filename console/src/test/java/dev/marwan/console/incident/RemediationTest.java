package dev.marwan.console.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.chaos.ChaosServiceTest;
import dev.marwan.console.slo.SloReading;

class RemediationTest {

    IncidentWatcherTest.MovingClock clock;
    IncidentStore store;
    IncidentWatcher watcher;
    /** Writes that remember each autoscaler's minimum, starting at the manifest's 2. */
    static class Hpas extends ChaosServiceTest.Writes {
        final java.util.Map<String, Integer> min = new java.util.HashMap<>(java.util.Map.of("booking-service", 2, "queue-gate", 2));
        @Override public void setHpaMin(String h, int n) { super.setHpaMin(h, n); min.put(h, n); }
        @Override public int hpaMin(String h) { return min.get(h); }
    }

    Hpas writes;
    RevertStore reverts;
    ChaosService chaos;
    Remediation remediation;
    String id;

    @BeforeEach
    void setUp() {
        clock = new IncidentWatcherTest.MovingClock();
        store = new IncidentStore(new ChaosServiceTest.Maps());
        SloReading breach = new SloReading(clock.now, true, null, true, 0.9, 3.0);
        watcher = new IncidentWatcher(() -> breach, store, Optional::empty, List::of, List::of, clock, i -> { });
        watcher.drillStarted(new ChaosService.ActiveFault("squeeze-pool", clock.now, clock.now.plusSeconds(120)));
        writes = new Hpas();
        chaos = mock(ChaosService.class);
        reverts = new RevertStore(new ChaosServiceTest.Maps());
        remediation = new Remediation(writes, chaos, watcher, reverts, clock);
        id = store.open().orElseThrow().id;
    }

    void propose(String action, String target, Integer replicas) {
        watcher.update(id, i -> i.proposals.add(new Incident.Proposal(i.proposals.size() + 1, clock.now, action, target,
                replicas, "because", List.of("F1"), "pending", null)));
    }

    @Test
    void scalingBookingServiceScalesIt_RaisesItsMinimum_AndSchedulesTheUndo() {
        propose("scale-booking", "booking-service", 3);
        assertThat(remediation.approve(id, 1)).isEqualTo(Remediation.Result.APPLIED);
        assertThat(writes.calls).containsExactly("scale booking-service 3", "hpa booking-service 3");
        Incident i = store.get(id).orElseThrow();
        assertThat(i.status).isEqualTo("mitigating");
        assertThat(i.proposals.get(0).status()).isEqualTo("approved");
        assertThat(reverts.pending()).singleElement().satisfies(r -> {
            assertThat(r.hpa()).isEqualTo("booking-service");
            assertThat(r.original()).isEqualTo(2);
        });
        assertThat(i.timeline).anyMatch(e -> e.source().equals("human") && e.text().contains("approved"));
    }

    @Test
    void theTemporaryRaiseIsUndoneAfterTenMinutes() {
        propose("raise-hpa-min", "queue-gate", 4);
        remediation.approve(id, 1);
        writes.calls.clear();
        clock.advance(599);
        remediation.revertDue();
        assertThat(writes.calls).isEmpty();
        clock.advance(2);
        remediation.revertDue();
        assertThat(writes.calls).containsExactly("hpa queue-gate 2");
        assertThat(reverts.pending()).isEmpty();
    }

    /** Review I3: a second raise must not record the first raise as the thing to go back to. */
    @Test
    void twoRaisesInARowStillRevertToTheOriginalMinimum() {
        propose("raise-hpa-min", "booking-service", 3);
        remediation.approve(id, 1);
        clock.advance(300);
        propose("raise-hpa-min", "booking-service", 4);
        remediation.approve(id, 2);
        writes.calls.clear();
        clock.advance(601);
        remediation.revertDue();
        assertThat(writes.calls).containsExactly("hpa booking-service 2");
        assertThat(writes.min.get("booking-service")).isEqualTo(2);
    }

    @Test
    void aScaleAndARaiseTogetherStillRevertToTheOriginal() {
        propose("scale-booking", "booking-service", 3);
        remediation.approve(id, 1);
        propose("raise-hpa-min", "booking-service", 4);
        remediation.approve(id, 2);
        clock.advance(700);
        remediation.revertDue();
        assertThat(writes.min.get("booking-service")).isEqualTo(2);
    }

    @Test
    void fewerThanTwoOrMoreThanFourReplicasIsRefused() {
        propose("scale-booking", "booking-service", 1);
        assertThat(remediation.approve(id, 1)).isEqualTo(Remediation.Result.FAILED);
        propose("raise-hpa-min", "queue-gate", 9);
        assertThat(remediation.approve(id, 2)).isEqualTo(Remediation.Result.FAILED);
        assertThat(writes.calls).isEmpty();
    }

    @Test
    void restartingAndEndingTheFault() {
        propose("restart-booking", "booking-service", null);
        remediation.approve(id, 1);
        propose("end-fault", null, null);
        remediation.approve(id, 2);
        assertThat(writes.calls).containsExactly("restart booking-service");
        verify(chaos).end();
    }

    @Test
    void approvingTwiceOrOnAClosedIncidentIsRefusedAndAppliesNothingAgain() {
        propose("restart-booking", "booking-service", null);
        remediation.approve(id, 1);
        assertThat(remediation.approve(id, 1)).isEqualTo(Remediation.Result.NOT_PENDING);
        propose("restart-booking", "booking-service", null);
        watcher.update(id, i -> i.status = "resolved");
        assertThat(remediation.approve(id, 2)).isEqualTo(Remediation.Result.NOT_PENDING);
        assertThat(writes.calls).containsExactly("restart booking-service");
        assertThat(remediation.approve("inc-nope", 1)).isEqualTo(Remediation.Result.NOT_FOUND);
    }

    @Test
    void aWriteTheClusterRefusesIsRecordedNotHidden() {
        propose("restart-booking", "booking-service", null);
        ChaosServiceTest.Writes refusing = new ChaosServiceTest.Writes() {
            @Override public void restart(String d) { throw new IllegalStateException("forbidden: deployments patch"); }
        };
        Remediation r = new Remediation(refusing, chaos, watcher, reverts, clock);
        assertThat(r.approve(id, 1)).isEqualTo(Remediation.Result.FAILED);
        Incident i = store.get(id).orElseThrow();
        assertThat(i.proposals.get(0).status()).isEqualTo("failed");
        assertThat(i.timeline).anyMatch(e -> e.source().equals("action") && e.text().contains("forbidden"));
    }

    @Test
    void dismissingLeavesTheClusterAlone() {
        propose("restart-booking", "booking-service", null);
        assertThat(remediation.dismiss(id, 1)).isEqualTo(Remediation.Result.DISMISSED);
        assertThat(writes.calls).isEmpty();
        assertThat(store.get(id).orElseThrow().proposals.get(0).status()).isEqualTo("dismissed");
    }
}
