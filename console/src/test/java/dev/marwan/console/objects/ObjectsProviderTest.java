package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
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

    /**
     * The inspector's empty state polls this list. An unreadable cluster must
     * leave it empty, not fail the request: found running locally against an
     * expired token, where it answered 500.
     */
    @Test
    void recentJobsFromAnUnreadableClusterAreEmptyNotAnError() {
        source.failWith = new IllegalStateException("Unauthorized");

        assertThat(provider.recentJobs()).isEmpty();
        assertThat(source.resets).isEqualTo(1);
    }

    /**
     * Review finding 2: a read that takes time (a Route probe waiting out its
     * 3s timeout) must still be reused by the next poll. Stamped with the time
     * the read began, it was already expired when stored.
     */
    @Test
    void aSlowReadIsStillServedFromCacheAfterItFinishes() {
        source.pods.add(pod("queue-gate-x"));
        source.onRead = () -> now.set(now.get().plus(Duration.ofMillis(1500)));

        provider.describe("pod", "queue-gate-x");
        int afterFirst = source.reads;
        provider.describe("pod", "queue-gate-x");

        assertThat(source.reads).isEqualTo(afterFirst);
    }

    /** Review finding 3: a 404 is remembered for the TTL, so looping on an unknown name is not one API read per request. */
    @Test
    void aNotFoundIsCachedForTheTtl() {
        assertThatThrownBy(() -> provider.describe("pod", "nope")).isInstanceOf(ObjectNotFound.class);
        int afterFirst = source.reads;

        assertThatThrownBy(() -> provider.describe("pod", "nope")).isInstanceOf(ObjectNotFound.class);
        assertThat(source.reads).isEqualTo(afterFirst);
    }

    /** Review finding 3: names come from a public URL, so the cache must not grow with them. */
    @Test
    void theCacheStaysBoundedWhateverNamesAreAskedFor() {
        source.failWith = new IllegalStateException("API server refused");

        for (int i = 0; i < 5_000; i++) {
            provider.describe("pod", "random-" + i);
        }

        assertThat(provider.cachedEntries()).isLessThanOrEqualTo(ObjectsProvider.MAX_ENTRIES);
    }

    /**
     * Review finding 4: a 403 (RBAC not applied yet) is a permission answer
     * from a working connection. Resetting the shared client for it churns the
     * client every other panel uses.
     */
    @Test
    void aForbiddenReadDoesNotResetTheSharedClient() {
        source.failWith = new KubernetesClientException("forbidden", 403, null);

        ObjectDetail detail = provider.describe("deployment", "queue-gate");

        assertThat(detail.available()).isFalse();
        assertThat(source.resets).isZero();
    }

    /** Review finding 5: every viewer with nothing selected polls this; ten must cost what one does. */
    @Test
    void recentJobsAreCachedForTheTtl() {
        provider.recentJobs();
        int afterFirst = source.reads;
        provider.recentJobs();

        assertThat(source.reads).isEqualTo(afterFirst);
    }

    private static Pod pod(String name) {
        return new PodBuilder().withNewMetadata().withName(name).addToLabels("app", "queue-gate").endMetadata()
                .withNewStatus().withPhase("Running").endStatus().build();
    }
}
