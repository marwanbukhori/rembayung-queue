package dev.marwan.console.ops;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LoadOpsTest {

    private final KubernetesAccess kubernetes = mock(KubernetesAccess.class);

    private final LoadOps ops = new LoadOps(
            properties(),
            kubernetes,
            RestClient.builder().baseUrl("http://queue-gate:8080").build(),
            Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC),
            rate -> { throw new AssertionError("no second sitting expected"); });

    private static Map<String, String> env(Job job) {
        return job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
    }

    @Test
    void twoWavesPassTheSecondSittingToTheJob() {
        Job job = ops.job("load-d-1", "d-1", 4242, 200, new LoadOps.Wave2("d-wave2", 77));

        assertThat(env(job)).containsEntry("WAVES", "2").containsEntry("DROP_ID_2", "d-wave2")
                .containsEntry("SLOT_ID_2", "77").containsEntry("WAVE_GAP", "3m").containsEntry("DROP_ID", "d-1");
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(600L);
        assertThat(job.getMetadata().getAnnotations()).containsEntry("rembayung.dev/waves", "2")
                .containsEntry("rembayung.dev/wave2-drop", "d-wave2")
                .containsEntry("rembayung.dev/wave-gap-seconds", "180");
    }

    @Test
    void oneWaveIsUnchanged() {
        Job job = ops.job("load-d-1", "d-1", 4242, 200, null);

        assertThat(env(job)).doesNotContainKeys("WAVES", "DROP_ID_2", "SLOT_ID_2", "WAVE_GAP");
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(300L);
        assertThat(job.getMetadata().getAnnotations()).doesNotContainKey("rembayung.dev/waves");
    }

    @Test
    void wavesOtherThanOneOrTwoAre400() {
        assertThat(LoadOps.wavesOf(new LoadOps.SendLoad(200, null))).isEqualTo(1);
        assertThat(LoadOps.wavesOf(new LoadOps.SendLoad(200, 2))).isEqualTo(2);
        assertThatThrownBy(() -> LoadOps.wavesOf(new LoadOps.SendLoad(200, 3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @SuppressWarnings("unchecked")
    private final MixedOperation<Job, JobList, ScalableResource<Job>> jobs = mock(MixedOperation.class);
    @SuppressWarnings("unchecked")
    private final NonNamespaceOperation<Job, JobList, ScalableResource<Job>> inNamespace = mock(NonNamespaceOperation.class);
    @SuppressWarnings("unchecked")
    private final ScalableResource<Job> named = mock(ScalableResource.class);

    /** A LoadOps whose gate knows drop d-1 at 8/s and whose cluster has the given Job under the run's name. */
    private LoadOps twoWaveOps(KubernetesClient client, Job existing, Function<Integer, DropOps.Sandbox> sittings) {
        RestClient.Builder gate = RestClient.builder().baseUrl("http://queue-gate:8080");
        MockRestServiceServer.bindTo(gate).build()
                .expect(requestTo("http://queue-gate:8080/internal/drops/d-1/state"))
                .andRespond(withSuccess("{\"dropId\":\"d-1\",\"slotId\":4242,\"admitRate\":8}",
                        MediaType.APPLICATION_JSON));
        given(kubernetes.client()).willReturn(client);
        // fabric8's DSL is generic, which deep stubs cannot follow: the job operations are mocked by hand.
        given(client.batch().v1().jobs()).willReturn(jobs);
        given(jobs.inNamespace("marwanbukhori-dev")).willReturn(inNamespace);
        given(inNamespace.withName("load-d-1")).willReturn(named);
        given(named.get()).willReturn(existing);
        return new LoadOps(properties(), kubernetes, gate.build(),
                Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC), sittings);
    }

    @Test
    void aSecondSittingThatFailsRefusesTheRunWith409AndCreatesNoJob() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        LoadOps failing = twoWaveOps(client, null, rate -> { throw new IllegalStateException("booking-service down"); });

        assertThatThrownBy(() -> failing.start("d-1", new LoadOps.SendLoad(200, 2)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).contains("wave 2");
                });
        verify(inNamespace, never()).resource(any(Job.class));
    }

    @Test
    void aSecondTwoWaveRunWhileOneIsLiveIsRefusedBeforeASittingIsCreated() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        Job live = new JobBuilder().withNewMetadata().withName("load-d-1").endMetadata()
                .withNewStatus().withActive(1).endStatus().build();
        AtomicInteger created = new AtomicInteger();
        LoadOps busy = twoWaveOps(client, live, rate -> {
            created.incrementAndGet();
            return new DropOps.Sandbox("d-wave2", 77, rate);
        });

        assertThatThrownBy(() -> busy.start("d-1", new LoadOps.SendLoad(200, 2)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(created).hasValue(0);
    }

    @Test
    void currentWaveFollowsElapsedTime() {
        assertThat(LoadOps.currentWave(2, 30, LoadRun.Phase.RUNNING)).isEqualTo(1);
        assertThat(LoadOps.currentWave(2, 150, LoadRun.Phase.RUNNING)).isEqualTo(0);
        assertThat(LoadOps.currentWave(2, 200, LoadRun.Phase.RUNNING)).isEqualTo(2);
        assertThat(LoadOps.currentWave(1, 30, LoadRun.Phase.RUNNING)).isEqualTo(1);
        assertThat(LoadOps.currentWave(2, 30, LoadRun.Phase.SUCCEEDED)).isEqualTo(0);
    }

    /**
     * One run per drop, enforced by the name rather than by a counter.
     *
     * This is the whole concurrency control: a second create of the same name
     * is refused by the API server, which cannot get out of step with reality
     * the way a number this console kept would.
     */
    @Test
    void theJobIsNamedAfterTheDrop() {
        assertThat(LoadOps.jobName("d-abc12345")).isEqualTo("load-d-abc12345");
        assertThat(LoadOps.jobName("default")).isEqualTo("load-default");
    }

    /**
     * Drop ids are generated by the gate, but the name has to be a legal
     * Kubernetes object name whatever arrives: lowercase, no leading or
     * trailing dash, and short enough to leave room for the pod's own suffixes.
     */
    @Test
    void theJobNameIsALegalKubernetesName() {
        String name = LoadOps.jobName("D-ABC_123!!");

        assertThat(name).isEqualTo("load-d-abc-123");
        assertThat(name).matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
        assertThat(LoadOps.jobName("!!!")).isEqualTo("load-unnamed");
        assertThat(LoadOps.jobName("x".repeat(200))).hasSizeLessThanOrEqualTo(53);
    }

    /**
     * A bigger run must cost more CPU, or the largest offer could never be the
     * one the quota refuses — and the constraints panel would have nothing to
     * explain, which is the point of offering it.
     */
    @Test
    void aBiggerRunAsksTheSchedulerForMore() {
        assertThat(LoadOps.cpuMillis(200)).isEqualTo(200);
        assertThat(LoadOps.cpuMillis(1000)).isEqualTo(400);
        assertThat(LoadOps.cpuMillis(3000)).isEqualTo(800);
        assertThat(LoadOps.memoryMebibytes(200)).isLessThan(LoadOps.memoryMebibytes(3000));
    }

    /**
     * Reading a run's state must never fail the request. The page polls this
     * beside the queue numbers, and those come from services that have nothing
     * to do with the Kubernetes API.
     */
    @Test
    void anUnreadableClusterIsReportedRatherThanThrown() {
        given(kubernetes.client()).willThrow(new IllegalStateException("no such host: kubernetes"));

        LoadRun run = ops.status("d-abc12345");

        assertThat(run.available()).isFalse();
        assertThat(run.detail()).contains("no such host");
        assertThat(run.phase()).isEqualTo(LoadRun.Phase.NONE);
    }

    /**
     * 60, so the default run finishes while somebody is still watching it.
     *
     * It was 200, chosen as the ceiling of usefulness. At the one admission a
     * second this database actually commits, 200 customers take over three
     * minutes to get through and the k6 script stops polling after ninety
     * seconds, so most of them would give up before their turn. 60 drains in a
     * minute, inside that window.
     */
    @Test
    void theDefaultCrowdFitsInsideThePollWindow() {
        assertThat(LoadOps.DEFAULT_VUS).isEqualTo(60);
    }

    private static ConsoleProperties properties() {
        return new ConsoleProperties("http://booking-service:8081", "http://queue-gate:8080",
                "default", 1, Duration.ofSeconds(2), Duration.ofSeconds(1), "marwanbukhori-dev",
                "s3cret-demo-key", "compute-deploy", "grafana/k6:0.53.0",
                new ConsoleProperties.Pool("booking-service", 5, 20));
    }
}
