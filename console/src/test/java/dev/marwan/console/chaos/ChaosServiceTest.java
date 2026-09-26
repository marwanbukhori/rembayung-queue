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

    static Pod pod(String name, String created, boolean ready) {
        return new PodBuilder().withNewMetadata().withName(name).withCreationTimestamp(created)
                .addToLabels("app", "booking-service").endMetadata()
                .withNewStatus().withPhase("Running").addNewContainerStatus().withReady(ready).withName("c")
                .withRestartCount(0).endContainerStatus().endStatus().build();
    }

    MovingClock clock;
    Maps maps;
    Writes writes;
    MockRestServiceServer booking;
    List<ChaosService.ActiveFault> drills;
    List<Pod> pods;
    ChaosService chaos;

    @BeforeEach
    void setUp() {
        clock = new MovingClock();
        maps = new Maps();
        writes = new Writes();
        drills = new ArrayList<>();
        pods = new ArrayList<>(List.of(pod("booking-service-old", "2026-09-26T10:00:00Z", true),
                pod("booking-service-new", "2026-09-26T11:00:00Z", true),
                pod("booking-service-newest-unready", "2026-09-26T11:30:00Z", false)));
        RestClient.Builder builder = RestClient.builder().baseUrl("http://booking-service:8081");
        booking = MockRestServiceServer.bindTo(builder).build();
        chaos = new ChaosService(maps, () -> pods, writes, builder.build(), drills::add, clock);
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
    void anInAppFaultIsSentToBookingService() {
        booking.expect(requestTo("http://booking-service:8081/internal/chaos")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"fault\":\"squeeze-pool\"}", MediaType.APPLICATION_JSON));
        ChaosService.ActiveFault f = chaos.inject("squeeze-pool");
        booking.verify();
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
    void anExpiredLockIsReplaced() {
        chaos.inject("kill-booking-pod");
        clock.now = clock.now.plus(Duration.ofSeconds(91));
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
    void endingAFaultEarlyClearsItInBookingServiceAndReleasesTheLock() {
        booking.expect(requestTo("http://booking-service:8081/internal/chaos")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        booking.expect(requestTo("http://booking-service:8081/internal/chaos")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        chaos.inject("slow-database");
        chaos.end();
        booking.verify();
        assertThat(chaos.current()).isEmpty();
    }
}
