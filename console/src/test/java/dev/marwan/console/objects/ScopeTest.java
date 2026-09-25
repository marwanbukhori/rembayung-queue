package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeTest {

    @Test
    void anythingKustomizeLabelledAsThisProjectIsOurs() {
        Service service = new ServiceBuilder().withNewMetadata().withName("queue-gate")
                .addToLabels("app.kubernetes.io/part-of", "rembayung-queue").endMetadata().build();

        assertThat(Scope.ours(service)).isTrue();
    }

    /** Pod templates do not carry the part-of label; the app label is what they share. */
    @Test
    void podsOfOurFourWorkloadsAndLoadRunsAreOurs() {
        for (String app : new String[]{"console", "queue-gate", "booking-service", "redis", "rembayung-load"}) {
            assertThat(Scope.ours(pod("x", app))).as(app).isTrue();
        }
    }

    @Test
    void keepaliveJobsAndTheirPodsAreOursThroughTheirOwners() {
        Job job = new JobBuilder().withNewMetadata().withName("keepalive-29838360")
                .addNewOwnerReference().withKind("CronJob").withName("keepalive").endOwnerReference()
                .endMetadata().build();
        Pod pod = new PodBuilder().withNewMetadata().withName("keepalive-29838360-zg4hz")
                .addNewOwnerReference().withKind("Job").withName("keepalive-29838360").endOwnerReference()
                .endMetadata().build();

        assertThat(Scope.ours(job)).isTrue();
        assertThat(Scope.ours(pod)).isTrue();
    }

    /** `oc debug` strips labels. A debug pod, or anything else unlabelled, is not ours to describe. */
    @Test
    void anUnlabelledPodIsNotOurs() {
        Pod debug = new PodBuilder().withNewMetadata().withName("console-debug-fbfbz").endMetadata().build();

        assertThat(Scope.ours(debug)).isFalse();
        assertThat(Scope.ours(pod("x", "someone-else"))).isFalse();
    }

    private static Pod pod(String name, String app) {
        return new PodBuilder().withNewMetadata().withName(name).addToLabels("app", app).endMetadata().build();
    }
}
