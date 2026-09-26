package dev.marwan.console.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import dev.marwan.console.agent.AnalysisStore;

public class ChaosServiceTest {

    static class MovingClock extends Clock {
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** ConfigMaps in memory, with resourceVersions and an optional forced conflict. */
    public static class Maps implements AnalysisStore.ConfigMapPort {
        final Map<String, ConfigMap> stored = new java.util.HashMap<>();
        int version;
        boolean conflictNext;
        @Override public Optional<ConfigMap> get(String name) {
            return Optional.ofNullable(stored.get(name)).map(c -> new ConfigMapBuilder(c).build());
        }
        @Override public void create(ConfigMap map) {
            if (conflictNext || stored.containsKey(map.getMetadata().getName())) {
                conflictNext = false;
                throw new AnalysisStore.Conflict();
            }
            map.getMetadata().setResourceVersion(String.valueOf(++version));
            stored.put(map.getMetadata().getName(), map);
        }
        @Override public void update(ConfigMap map) {
            ConfigMap current = stored.get(map.getMetadata().getName());
            if (conflictNext || current == null
                    || !current.getMetadata().getResourceVersion().equals(map.getMetadata().getResourceVersion())) {
                conflictNext = false;
                throw new AnalysisStore.Conflict();
            }
            map.getMetadata().setResourceVersion(String.valueOf(++version));
            stored.put(map.getMetadata().getName(), map);
        }
    }

    public static class Writes implements ClusterWrites {
        public final List<String> calls = new ArrayList<>();
        @Override public void deletePod(String name) { calls.add("delete " + name); }
        @Override public void scale(String d, int n) { calls.add("scale " + d + " " + n); }
        @Override public void restart(String d) { calls.add("restart " + d); }
        @Override public void setHpaMin(String h, int n) { calls.add("hpa " + h + " " + n); }
        @Override public int hpaMin(String h) { return 2; }
    }

    /** booking-service's /internal/chaos, per pod IP, recorded. */
    public static class Booking implements BookingChaos {
        public final List<String> calls = new ArrayList<>();
        public boolean failStart;
        @Override public void start(String podIp, String fault, int seconds) {
            if (failStart) {
                throw new IllegalStateException("booking-service unreachable");
            }
            calls.add("start " + podIp + " " + fault + " " + seconds);
        }
        @Override public void stop(String podIp) { calls.add("stop " + podIp); }
    }

    static Pod pod(String name, String created, boolean ready, String ip) {
        return new PodBuilder().withNewMetadata().withName(name).withCreationTimestamp(created)
                .addToLabels("app", "booking-service").endMetadata()
                .withNewStatus().withPhase("Running").withPodIP(ip).addNewContainerStatus().withReady(ready).withName("c")
                .withRestartCount(0).endContainerStatus().endStatus().build();
    }

    MovingClock clock;
    Maps maps;
    Writes writes;
    Booking booking;
    List<ChaosService.ActiveFault> drills;
    List<Pod> pods;
    ChaosService chaos;

    @BeforeEach
    void setUp() {
        clock = new MovingClock();
        maps = new Maps();
        writes = new Writes();
        booking = new Booking();
        drills = new ArrayList<>();
        pods = new ArrayList<>(List.of(pod("booking-service-old", "2026-09-26T10:00:00Z", true, "10.0.0.1"),
                pod("booking-service-new", "2026-09-26T11:00:00Z", true, "10.0.0.2"),
                pod("booking-service-newest-unready", "2026-09-26T11:30:00Z", false, "10.0.0.3")));
        chaos = new ChaosService(maps, () -> pods, writes, booking, drills::add, clock);
    }

    @Test
    void killingAPodDeletesTheNewestReadyBookingPodAndOpensADrill() {
        ChaosService.ActiveFault f = chaos.inject("kill-booking-pod");
        assertThat(writes.calls).containsExactly("delete booking-service-new");
        assertThat(f.until()).isEqualTo(clock.now.plusSeconds(90));
        assertThat(drills).containsExactly(f);
        assertThat(chaos.current()).contains(f);
    }

    @Test
    void anInAppFaultReachesEveryBookingPodNotJustOne() {
        ChaosService.ActiveFault f = chaos.inject("squeeze-pool");
        assertThat(booking.calls).containsExactlyInAnyOrder("start 10.0.0.1 squeeze-pool 120",
                "start 10.0.0.2 squeeze-pool 120", "start 10.0.0.3 squeeze-pool 120");
        assertThat(f.until()).isEqualTo(clock.now.plusSeconds(120));
    }

    @Test
    void aSecondFaultWhileOneIsActiveIsBusyAndNamesTheActiveOne() {
        chaos.inject("kill-booking-pod");
        assertThatThrownBy(() -> chaos.inject("kill-booking-pod")).isInstanceOf(ChaosService.Busy.class)
                .hasMessageContaining("kill-booking-pod");
        assertThat(writes.calls).hasSize(1);
    }

    @Test
    void theCooldownRunsFromTheStartSoKillEndKillCannotLoop() {
        chaos.inject("kill-booking-pod");
        chaos.end();
        clock.now = clock.now.plusSeconds(30);
        assertThatThrownBy(() -> chaos.inject("kill-booking-pod")).isInstanceOf(ChaosService.Busy.class);
        assertThatThrownBy(() -> chaos.inject("squeeze-pool")).isInstanceOf(ChaosService.Busy.class);
        assertThat(writes.calls).hasSize(1);
    }

    @Test
    void endingAnInAppFaultEarlyStillHoldsTheCooldown() {
        chaos.inject("slow-database");
        chaos.end();
        assertThat(chaos.current()).isEmpty();
        clock.now = clock.now.plusSeconds(60);
        assertThatThrownBy(() -> chaos.inject("squeeze-pool")).isInstanceOf(ChaosService.Busy.class);
        clock.now = clock.now.plusSeconds(61);
        chaos.inject("squeeze-pool");
    }

    @Test
    void endingAPodKillLeavesTheLockAlone() {
        ChaosService.ActiveFault f = chaos.inject("kill-booking-pod");
        chaos.end();
        assertThat(chaos.current()).contains(f);
    }

    @Test
    void aKillIsRefusedWhenFewerThanTwoBookingPodsAreReady() {
        pods.removeIf(p -> p.getMetadata().getName().equals("booking-service-old"));
        assertThatThrownBy(() -> chaos.inject("kill-booking-pod")).isInstanceOf(ChaosService.Refused.class)
                .hasMessageContaining("2 ready");
        assertThat(writes.calls).isEmpty();
        assertThat(chaos.current()).isEmpty();
    }

    @Test
    void anExpiredLockIsReplaced() {
        chaos.inject("kill-booking-pod");
        clock.now = clock.now.plus(Duration.ofSeconds(121));
        assertThat(chaos.current()).isEmpty();
        chaos.inject("kill-booking-pod");
        assertThat(writes.calls).hasSize(2);
    }

    @Test
    void twoClicksAtOnceMeanOneWins() {
        maps.conflictNext = true;
        assertThatThrownBy(() -> chaos.inject("kill-booking-pod")).isInstanceOf(ChaosService.Busy.class);
        assertThat(writes.calls).isEmpty();
    }

    @Test
    void anUnknownFaultIsRefused() {
        assertThatThrownBy(() -> chaos.inject("drop-tables")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endingAFaultEarlyClearsItOnEveryBookingPod() {
        chaos.inject("slow-database");
        chaos.end();
        assertThat(booking.calls).contains("stop 10.0.0.1", "stop 10.0.0.2", "stop 10.0.0.3");
        assertThat(chaos.current()).isEmpty();
    }

    @Test
    void aFaultThatCannotBeAppliedReleasesTheLock() {
        booking.failStart = true;
        assertThatThrownBy(() -> chaos.inject("slow-database")).isInstanceOf(ChaosService.ApplyFailed.class);
        assertThat(chaos.current()).isEmpty();
        assertThat(drills).isEmpty();
        booking.failStart = false;
        chaos.inject("slow-database");
    }
}
