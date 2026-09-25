package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectsProviderTest {

    private final FakeObjectSource source = new FakeObjectSource();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T08:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final ObjectsProvider provider = new ObjectsProvider(source, clock);

    @Test
    void anUnknownKindIsNotFound() {
        assertThatThrownBy(() -> provider.describe("secret", "splunk-hec")).isInstanceOf(ObjectNotFound.class);
    }

    /** Review focus 1: a pod deleted by a rollout between two polls. */
    @Test
    void aPodThatNoLongerExistsIsNotFound() {
        assertThatThrownBy(() -> provider.describe("pod", "queue-gate-gone")).isInstanceOf(ObjectNotFound.class);
    }

    /** Review focus 4: a pod in our namespace that is not ours. */
    @Test
    void aPodOutsideTheProjectIsNotFoundEvenThoughItExists() {
        source.pods.add(new PodBuilder().withNewMetadata().withName("console-debug-fbfbz").endMetadata().build());

        assertThatThrownBy(() -> provider.describe("pod", "console-debug-fbfbz")).isInstanceOf(ObjectNotFound.class);
    }

    @Test
    void anUnreadableClusterIsAReasonNotAnException() {
        source.failWith = new IllegalStateException("API server refused");

        ObjectDetail detail = provider.describe("pod", "queue-gate-x");

        assertThat(detail.available()).isFalse();
        assertThat(detail.detail()).contains("API server refused");
        assertThat(source.resets).isEqualTo(1);
    }

    @Test
    void aSecondReadWithinTwoSecondsIsServedFromCache() {
        source.pods.add(pod("queue-gate-x"));

        provider.describe("pod", "queue-gate-x");
        int afterFirst = source.reads;
        provider.describe("pod", "queue-gate-x");
        assertThat(source.reads).isEqualTo(afterFirst);

        now.set(now.get().plus(Duration.ofSeconds(3)));
        provider.describe("pod", "queue-gate-x");
        assertThat(source.reads).isGreaterThan(afterFirst);
    }

    @Test
    void recentJobsAreNewestFirst() {
        source.jobs.add(new JobBuilder().withNewMetadata().withName("keepalive-1")
                .addNewOwnerReference().withKind("CronJob").withName("keepalive").endOwnerReference().endMetadata()
                .withNewStatus().withStartTime("2026-09-25T02:00:00Z").endStatus().build());
        source.jobs.add(new JobBuilder().withNewMetadata().withName("load-d-3fa951d5")
                .addToLabels("app", "rembayung-load").endMetadata()
                .withNewStatus().withStartTime("2026-09-25T07:23:00Z").endStatus().build());

        assertThat(provider.recentJobs()).extracting(ObjectSummary::name)
                .containsExactly("load-d-3fa951d5", "keepalive-1");
    }

    private static Pod pod(String name) {
        return new PodBuilder().withNewMetadata().withName(name).addToLabels("app", "queue-gate").endMetadata()
                .withNewStatus().withPhase("Running").endStatus().build();
    }
}
