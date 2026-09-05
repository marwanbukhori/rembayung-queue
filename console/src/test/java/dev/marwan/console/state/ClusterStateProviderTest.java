package dev.marwan.console.state;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.cluster.KubernetesAccess;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

class ClusterStateProviderTest {

    private final KubernetesAccess kubernetes = mock(KubernetesAccess.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC);

    private final ClusterStateProvider provider =
            new ClusterStateProvider(properties(), kubernetes, clock);

    /**
     * The whole failure contract in one test: an unreachable API server is a
     * sentence on the page, not an exception out of the provider. A console
     * that threw here would take the drop panel down with the cluster panel,
     * and the drop numbers come from somewhere else entirely.
     */
    @Test
    void anUnreachableApiServerBecomesAReasonRatherThanAnException() {
        given(kubernetes.client()).willThrow(new IllegalStateException("API server refused"));

        ClusterState state = provider.current();

        assertThat(state.available()).isFalse();
        assertThat(state.detail()).contains("API server refused");
        assertThat(state.consumers()).isEmpty();
    }

    /**
     * A client whose call failed is dropped, so the next poll builds a fresh
     * one. Without this a single blip would leave the console holding a dead
     * connection and reporting the cluster unreadable until someone restarted
     * it.
     */
    @Test
    void aFailedReadInvalidatesTheClientSoTheNextPollReconnects() {
        given(kubernetes.client()).willThrow(new IllegalStateException("connection refused"));

        provider.current();

        then(kubernetes).should().invalidate();
    }

    /**
     * An Error, not just an Exception. A missing optional HTTP client on the
     * classpath arrives as a NoClassDefFoundError, and the console dying
     * because it could not describe a quota would be the wrong trade.
     */
    @Test
    void evenAnErrorIsCaught() {
        given(kubernetes.client()).willThrow(new NoClassDefFoundError("okhttp3/OkHttpClient"));

        assertThat(provider.current().available()).isFalse();
    }

    private static ConsoleProperties properties() {
        return new ConsoleProperties("http://booking-service:8081", "http://queue-gate:8080",
                "default", 1, Duration.ofSeconds(2), Duration.ofSeconds(1), "marwanbukhori-dev",
                "s3cret-demo-key", "compute-deploy", "grafana/k6:0.53.0",
                new ConsoleProperties.Pool("booking-service", 5, 20));
    }
}
