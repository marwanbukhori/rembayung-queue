package dev.marwan.console.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromTextTest {

    private static final String BODY = """
        # HELP hikaricp_connections_active Active connections
        # TYPE hikaricp_connections_active gauge
        hikaricp_connections_active{application="booking-service",pool="HikariPool-1"} 3.0
        hikaricp_connections_active_extra{pool="x"} 9.0
        http_server_requests_seconds_count{method="GET",status="200",uri="/queue/{token}"} 721
        http_server_requests_seconds_count{method="POST",status="503",uri="/bookings"} 4
        jvm_threads_live_threads 42.0
        """;

    @Test
    void samplesOfOneMetricWithTheirLabels() {
        var samples = PromText.samples(BODY, "http_server_requests_seconds_count");

        assertThat(samples).hasSize(2);
        assertThat(samples.getFirst().labels()).containsEntry("status", "200").containsEntry("uri", "/queue/{token}");
        assertThat(samples.getFirst().value()).isEqualTo(721);
    }

    @Test
    void aMetricNameIsMatchedExactlyNotAsAPrefix() {
        var samples = PromText.samples(BODY, "hikaricp_connections_active");

        assertThat(samples).hasSize(1);
        assertThat(samples.getFirst().value()).isEqualTo(3.0);
    }

    @Test
    void aMetricWithoutLabelsParses() {
        assertThat(PromText.samples(BODY, "jvm_threads_live_threads").getFirst().value()).isEqualTo(42.0);
    }
}
