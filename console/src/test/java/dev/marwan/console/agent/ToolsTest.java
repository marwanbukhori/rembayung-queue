package dev.marwan.console.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.EventBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marwan.console.metrics.RangeQuery;
import dev.marwan.console.metrics.Series;
import tools.jackson.databind.ObjectMapper;

class ToolsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final RunWindow WINDOW = new RunWindow("load-rush-1", "rush-1",
            Instant.parse("2026-09-25T13:40:00Z"), Instant.parse("2026-09-25T13:45:00Z"));

    FakeCluster cluster;
    RangeQuery prometheus;
    Facts facts;

    @BeforeEach
    void setUp() {
        cluster = new FakeCluster();
        cluster.pods.add(FakeCluster.pod("booking-service-a", "booking-service", 0));
        cluster.pods.add(FakeCluster.pod("someone-elses", "unrelated", 0));
        List<String> lines = new ArrayList<>();
        IntStream.range(0, 100).forEach(i -> lines.add(String.format(
                "2026-09-25T13:41:%02d.000Z {\"level\":\"WARN\",\"message\":\"line %d for +60123456789\"}", i % 60, i)));
        cluster.logs.put("booking-service-a", String.join("\n", lines));
        List<double[]> points = new ArrayList<>();
        IntStream.range(0, 100).forEach(i -> points.add(new double[] {i, i}));
        prometheus = (q, l, s, e, st) -> List.of(new Series("booking-service-a", points),
                new Series("booking-service-b", List.of(new double[] {1, 0})));
        facts = new Facts();
    }

    Fact call(String tool, String args) {
        return new Tools(cluster, prometheus).call(tool, JSON.readTree(args), WINDOW, facts);
    }

    @Test
    void podLogsReturnsAtMostFortyMaskedLines() {
        Fact fact = call("pod_logs", "{\"pod\":\"booking-service-a\",\"level\":\"WARN\"}");

        assertThat(fact.id()).isEqualTo("F1");
        assertThat(fact.source()).isEqualTo("tool: pod_logs");
        assertThat(fact.value().lines().count()).isEqualTo(40);
        assertThat(fact.value()).doesNotContain("123456789").contains("line 99");
    }

    @Test
    void podLogsFiltersByText() {
        Fact fact = call("pod_logs", "{\"pod\":\"booking-service-a\",\"contains\":\"line 42 \"}");
        assertThat(fact.value().lines()).hasSize(1);
    }

    @Test
    void aPodOutsideTheProjectIsRefused() {
        Fact fact = call("pod_logs", "{\"pod\":\"someone-elses\"}");
        assertThat(fact.value()).startsWith("refused:");
    }

    @Test
    void metricIsDownsampledToThirtyPoints() {
        Fact fact = call("metric", "{\"chart\":\"pool\",\"pod\":\"booking-service-a\"}");
        String values = fact.value().substring(fact.value().indexOf(':') + 1);
        assertThat(values.split(",")).hasSize(30);
        assertThat(fact.value()).doesNotContain("booking-service-b");
    }

    @Test
    void eventsAreCappedAtTwenty() {
        IntStream.range(0, 30).forEach(i -> cluster.events.computeIfAbsent("Pod/booking-service-a",
                k -> new ArrayList<>()).add(new EventBuilder().withType("Warning").withReason("Unhealthy" + i)
                .withMessage("probe failed").withLastTimestamp("2026-09-25T13:42:00Z").build()));
        Fact fact = call("events", "{\"kind\":\"Pod\",\"name\":\"booking-service-a\"}");
        assertThat(fact.value().lines()).hasSize(20);
    }

    @Test
    void anUnknownToolOrMissingArgumentIsAFactNotAnError() {
        assertThat(call("delete_everything", "{}").value()).startsWith("unknown tool");
        assertThat(call("pod_logs", "{}").value()).startsWith("bad arguments");
        assertThat(call("metric", "{\"chart\":\"cpu\"}").value()).startsWith("bad arguments");
    }

    @Test
    void podStatusSaysPhaseReadyAndRestarts() {
        Fact fact = call("pod_status", "{\"pod\":\"booking-service-a\"}");
        assertThat(fact.value()).contains("Running").contains("ready").contains("restarts 0");
    }
}
