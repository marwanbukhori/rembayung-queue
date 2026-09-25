package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PodLogsTest {

    private static final String EVENT = "2026-09-25T10:00:01.000000000Z "
            + "{\"message\":\"Claimed seats\",\"level\":\"INFO\",\"event\":\"booking.claimed\"}";
    private static final String NOISE = "2026-09-25T10:00:02.000000000Z {\"message\":\"Started\",\"level\":\"INFO\"}";

    private final FakeObjectSource source = new FakeObjectSource();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:05Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final PodLogs logs = new PodLogs(source, clock);

    @BeforeEach
    void pods() {
        source.pods.add(new PodBuilder().withNewMetadata().withName("booking-1")
                .addToLabels("app", "booking-service").endMetadata().build());
        source.pods.add(new PodBuilder().withNewMetadata().withName("redis-1")
                .addToLabels("app", "redis").endMetadata().build());
        source.log = EVENT + "\n" + NOISE;
    }

    @Test
    void aKeyHolderGetsWhatTheyAskFor() {
        LogPage page = logs.read("booking-1", null, "all", true);

        assertThat(page.restricted()).isFalse();
        assertThat(page.filter()).isEqualTo("all");
        assertThat(page.lines()).hasSize(2);
    }

    /** Review focus 3: the key rule is the server's, whatever the request says. */
    @Test
    void withoutTheKeyOnlyEventsComeBackEvenWhenAllIsAskedFor() {
        LogPage page = logs.read("booking-1", null, "all", false);

        assertThat(page.restricted()).isTrue();
        assertThat(page.filter()).isEqualTo("events");
        assertThat(page.lines()).extracting(LogLine::event).containsExactly("booking.claimed");
    }

    @Test
    void redisIsShownWholeToEveryoneBecauseItLogsOnlyItsLifecycle() {
        LogPage page = logs.read("redis-1", null, "all", false);

        assertThat(page.restricted()).isFalse();
        assertThat(page.lines()).hasSize(2);
        assertThat(page.note()).contains("lifecycle");
    }

    @Test
    void theCursorReturnsOnlyNewerLinesAndAdvances() {
        LogPage page = logs.read("booking-1", "2026-09-25T10:00:01.000000000Z", "all", true);

        assertThat(page.lines()).extracting(LogLine::message).containsExactly("Started");
        assertThat(page.latest()).isEqualTo("2026-09-25T10:00:02.000000000Z");
    }

    /** Review focus 4. */
    @Test
    void aGarbageSinceIsNoCursorAndAFutureOneIsAnEmptyPage() {
        assertThat(logs.read("booking-1", "yesterday", "all", true).lines()).hasSize(2);
        assertThat(logs.read("booking-1", "2099-01-01T00:00:00Z", "all", true).lines()).isEmpty();
    }

    @Test
    void manyViewersCostOneReadPerPodPerTwoSeconds() {
        logs.read("booking-1", null, "all", true);
        logs.read("booking-1", null, "events", false);
        logs.read("booking-1", "2026-09-25T10:00:01.000000000Z", "all", true);
        assertThat(source.logReads).isEqualTo(1);

        now.set(now.get().plus(Duration.ofSeconds(3)));
        logs.read("booking-1", null, "all", true);
        assertThat(source.logReads).isEqualTo(2);
    }

    @Test
    void aPodThatIsNotOursIsNotFound() {
        source.pods.add(new PodBuilder().withNewMetadata().withName("console-debug-x").endMetadata().build());

        assertThatThrownBy(() -> logs.read("console-debug-x", null, "all", true)).isInstanceOf(ObjectNotFound.class);
        assertThatThrownBy(() -> logs.read("nope", null, "all", true)).isInstanceOf(ObjectNotFound.class);
    }

    /** Review focus 1. */
    @Test
    void aContainerThatHasNotStartedHasNoLogsYetRatherThanAnError() {
        source.failLogWith = new KubernetesClientException(
                "container \"booking-service\" in pod \"booking-1\" is waiting to start: ContainerCreating", 400, null);

        LogPage page = logs.read("booking-1", null, "all", true);

        assertThat(page.available()).isTrue();
        assertThat(page.lines()).isEmpty();
        assertThat(page.note()).isEqualTo("No logs yet: the container has not started.");
        assertThat(source.resets).isZero();
    }

    @Test
    void anUnreadableClusterIsAReason() {
        source.failLogWith = new IllegalStateException("API server refused");

        LogPage page = logs.read("booking-1", null, "all", true);

        assertThat(page.available()).isFalse();
        assertThat(page.detail()).contains("API server refused");
        assertThat(source.resets).isEqualTo(1);
    }

    /**
     * Review finding 1: the API applies limitBytes from the start of the tail,
     * so a byte cap dropped the newest lines - and cut the last one in half -
     * exactly during an error burst. The tail alone bounds the read.
     */
    @Test
    void theReadAsksForTheTailByLineCountAlone() {
        logs.read("booking-1", null, "all", true);

        assertThat(source.lastTail).isEqualTo(PodLogs.TAIL);
    }
}
