package dev.marwan.console.metrics;

import dev.marwan.console.objects.ObjectSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class PodReadingsTest {

    private final ObjectSource source = mock(ObjectSource.class);
    private final Map<String, String> bodies = new HashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final PodReadings readings = new PodReadings(source, url -> {
        String body = bodies.get(url);
        if (body == null) {
            throw new IOException("connection refused");
        }
        return body;
    }, clock);

    @Test
    void poolIsReadFromEachBookingPod() {
        given(source.pods("booking-service")).willReturn(List.of(pod("booking-a", "10.0.0.1"), pod("booking-b", "10.0.0.2")));
        bodies.put("http://10.0.0.1:9090/actuator/prometheus", "hikaricp_connections_active{pool=\"HikariPool-1\"} 5.0\n");
        bodies.put("http://10.0.0.2:9090/actuator/prometheus", "hikaricp_connections_active{pool=\"HikariPool-1\"} 0.0\n");

        assertThat(readings.now(ChartName.POOL)).extracting(Reading::label, Reading::value)
                .containsExactly(tuple("booking-a", 5.0), tuple("booking-b", 0.0));
    }

    @Test
    void aPodThatCannotBeReadIsLeftOutNotFatal() {
        given(source.pods("booking-service")).willReturn(List.of(pod("booking-a", "10.0.0.1"), pod("booking-b", "10.0.0.2")));
        bodies.put("http://10.0.0.1:9090/actuator/prometheus", "hikaricp_connections_active{pool=\"HikariPool-1\"} 2.0\n");

        assertThat(readings.now(ChartName.POOL)).extracting(Reading::label).containsExactly("booking-a");
    }

    @Test
    void requestsPerSecondComeFromTheCounterDeltaBetweenReads() {
        given(source.pods("queue-gate")).willReturn(List.of(pod("gate-a", "10.0.0.9")));
        String url = "http://10.0.0.9:9090/actuator/prometheus";
        bodies.put(url, counter(200, 100) + counter(503, 10));
        assertThat(readings.now(ChartName.REQUESTS)).isEmpty();

        now.set(now.get().plusSeconds(10));
        bodies.put(url, counter(200, 150) + counter(503, 30));
        assertThat(readings.now(ChartName.REQUESTS)).extracting(Reading::label, Reading::value)
                .containsExactly(tuple("2xx", 5.0), tuple("5xx", 2.0));
    }

    /** Review focus 4: a restarted pod's counter drops; that is not negative traffic. */
    @Test
    void aCounterResetIsNeverNegative() {
        given(source.pods("queue-gate")).willReturn(List.of(pod("gate-a", "10.0.0.9")));
        String url = "http://10.0.0.9:9090/actuator/prometheus";
        bodies.put(url, counter(200, 500));
        readings.now(ChartName.REQUESTS);

        now.set(now.get().plusSeconds(10));
        bodies.put(url, counter(200, 20));
        assertThat(readings.now(ChartName.REQUESTS)).allSatisfy(r -> assertThat(r.value()).isGreaterThanOrEqualTo(0));
    }

    @Test
    void replicasComeFromTheAutoscalers() {
        given(source.hpa("queue-gate")).willReturn(Optional.of(new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withName("queue-gate").endMetadata()
                .withNewSpec().withMaxReplicas(10).endSpec()
                .withNewStatus().withCurrentReplicas(6).endStatus().build()));
        given(source.hpa("booking-service")).willReturn(Optional.empty());

        assertThat(readings.now(ChartName.REPLICAS)).extracting(Reading::label, Reading::value)
                .containsExactly(tuple("queue-gate", 6.0));
    }

    @Test
    void latencyHasNoLiveReading() {
        assertThat(readings.now(ChartName.LATENCY)).isEmpty();
    }

    private static String counter(int status, double value) {
        return "http_server_requests_seconds_count{method=\"GET\",status=\"" + status
                + "\",uri=\"/queue/{token}\"} " + value + "\n";
    }

    private static Pod pod(String name, String ip) {
        return new PodBuilder().withNewMetadata().withName(name).endMetadata()
                .withNewStatus().withPodIP(ip).withPhase("Running").endStatus().build();
    }
}
