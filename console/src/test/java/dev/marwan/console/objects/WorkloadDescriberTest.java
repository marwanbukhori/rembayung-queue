package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class WorkloadDescriberTest {

    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

    /**
     * The outage of 2026-09-25: desired 0, no status at all. It must read as
     * the problem it is, not as a quiet "0/0" that looks settled.
     */
    @Test
    void aDeploymentScaledToZeroIsBadAndSaysSo() {
        Deployment d = deployment("booking-service", 0, null);

        ObjectDetail detail = WorkloadDescriber.deployment(d, List.of(), null, List.of(), NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("scaled to 0 - nothing is serving");
    }

    @Test
    void aDeploymentShortOfReplicasIsAWarning() {
        ObjectDetail detail = WorkloadDescriber.deployment(
                deployment("queue-gate", 2, 1), List.of(), null, List.of(), NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.WARN);
        assertThat(detail.headline()).isEqualTo("1 of 2 available");
    }

    @Test
    void aHealthyDeploymentListsItsImageReplicaSetsAndPods() {
        ReplicaSet current = replicaSet("booking-service-874f94d9", "7", 2);
        ReplicaSet old = replicaSet("booking-service-55bf87f47c", "6", 0);
        Pod pod = new PodBuilder().withNewMetadata().withName("booking-service-874f94d9-mfnlb")
                .addToLabels("app", "booking-service").endMetadata()
                .withNewStatus().withPhase("Running").endStatus().build();

        ObjectDetail detail = WorkloadDescriber.deployment(
                deployment("booking-service", 2, 2), List.of(old, current), null, List.of(pod), NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.OK);
        assertThat(detail.headline()).isEqualTo("2 of 2 available");
        assertThat(detail.facts()).extracting(ObjectDetail.Fact::label, ObjectDetail.Fact::value)
                .contains(tuple("Image", "8abd7b73ff15"),
                        tuple("ReplicaSets", "874f94d9 current, 1 kept"));
        assertThat(detail.related()).extracting(ObjectDetail.Link::name)
                .contains("booking-service-874f94d9-mfnlb");
    }

    /** A pod that is not yet scheduled has no container statuses at all. */
    @Test
    void aPendingPodWithNoContainerStatusesDoesNotThrow() {
        Pod pending = new PodBuilder().withNewMetadata().withName("queue-gate-x")
                .withCreationTimestamp("2026-09-25T07:59:00Z").endMetadata()
                .withNewStatus().withPhase("Pending").endStatus().build();

        ObjectDetail detail = WorkloadDescriber.pod(pending, NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.WARN);
        assertThat(detail.headline()).isEqualTo("Pending");
    }

    @Test
    void aCrashLoopingPodIsBadAndNamesTheReason() {
        Pod crashing = new PodBuilder().withNewMetadata().withName("queue-gate-y").endMetadata()
                .withNewStatus().withPhase("Pending")
                .addNewInitContainerStatus().withName("dynatrace-agent").withRestartCount(6)
                    .withNewState().withNewWaiting().withReason("CrashLoopBackOff").endWaiting().endState()
                .endInitContainerStatus()
                .endStatus().build();

        ObjectDetail detail = WorkloadDescriber.pod(crashing, NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("CrashLoopBackOff in dynatrace-agent");
    }

    /**
     * An HPA whose target sits at 0 reports ScalingActive=False and stands
     * still. That is the second half of the 2026-09-25 outage.
     */
    @Test
    void anHpaThatHasStoppedScalingIsBad() {
        HorizontalPodAutoscaler hpa = new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withName("queue-gate").endMetadata()
                .withNewSpec().withMinReplicas(2).withMaxReplicas(10).endSpec()
                .withNewStatus().withCurrentReplicas(0).withDesiredReplicas(0)
                .addNewCondition().withType("ScalingActive").withStatus("False")
                    .withReason("ScalingDisabled")
                    .withMessage("scaling is disabled since the replica count of the target is zero")
                .endCondition()
                .endStatus().build();

        ObjectDetail detail = WorkloadDescriber.hpa(hpa, NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("not scaling: ScalingDisabled");
    }

    /** Freshly created: no status, no metrics. Still a description. */
    @Test
    void anHpaWithNoStatusYetDoesNotThrow() {
        HorizontalPodAutoscaler hpa = new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withName("booking-service").endMetadata()
                .withNewSpec().withMinReplicas(2).withMaxReplicas(4).endSpec().build();

        ObjectDetail detail = WorkloadDescriber.hpa(hpa, NOW);

        assertThat(detail.headline()).isEqualTo("0 of 2-4");
    }

    @Test
    void aCompletedJobReportsItsDuration() {
        Job job = new JobBuilder().withNewMetadata().withName("load-d-3fa951d5")
                .addToLabels("app", "rembayung-load").endMetadata()
                .withNewStatus().withSucceeded(1)
                    .withStartTime("2026-09-25T07:23:00Z").withCompletionTime("2026-09-25T07:24:50Z")
                .endStatus().build();

        ObjectDetail detail = WorkloadDescriber.job(job, List.of(), NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.OK);
        assertThat(detail.headline()).isEqualTo("Complete in 110s");
    }

    /** The keepalive's schedule is five-field cron in UTC. The next run is what a reader wants. */
    @Test
    void theCronJobSaysWhenItNextRuns() {
        CronJob keepalive = new CronJobBuilder().withNewMetadata().withName("keepalive").endMetadata()
                .withNewSpec().withSchedule("0 2,10,18 * * *").endSpec()
                .withNewStatus().withLastSuccessfulTime("2026-09-25T02:00:49Z").endStatus().build();

        ObjectDetail detail = WorkloadDescriber.cronJob(keepalive, List.of(), NOW);

        assertThat(detail.facts()).extracting(ObjectDetail.Fact::label, ObjectDetail.Fact::value)
                .contains(tuple("Next run", "2026-09-25T10:00:00Z"));
    }

    private static Deployment deployment(String name, int desired, Integer available) {
        return new DeploymentBuilder()
                .withNewMetadata().withName(name).endMetadata()
                .withNewSpec().withReplicas(desired)
                    .withNewStrategy().withNewRollingUpdate()
                        .withMaxSurge(new IntOrString(1)).withMaxUnavailable(new IntOrString(0))
                    .endRollingUpdate().endStrategy()
                    .withNewTemplate().withNewSpec().addNewContainer().withName(name)
                        .withImage("ghcr.io/marwanbukhori/" + name + ":8abd7b73ff15deadbeef")
                    .endContainer().endSpec().endTemplate()
                .endSpec()
                .withNewStatus().withAvailableReplicas(available).endStatus()
                .build();
    }

    private static ReplicaSet replicaSet(String name, String revision, int replicas) {
        return new ReplicaSetBuilder().withNewMetadata().withName(name)
                .addToAnnotations("deployment.kubernetes.io/revision", revision).endMetadata()
                .withNewSpec().withReplicas(replicas).endSpec().build();
    }
}
