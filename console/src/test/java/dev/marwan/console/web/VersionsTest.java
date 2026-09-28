package dev.marwan.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;

class VersionsTest {

    static final String A = "d48e30092e79f77c162956970a1b80dd070fcb07";
    static final String B = "86d3ae9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    static Deployment running(String name, String tag) {
        return new DeploymentBuilder().withNewMetadata().withName(name).endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                .addNewContainer().withName(name).withImage("ghcr.io/marwanbukhori/" + name + ":" + tag).endContainer()
                .endSpec().endTemplate().endSpec().build();
    }

    @Test
    void oneCommitWhenEveryServiceRunsTheSameImageTag() {
        Map<String, Deployment> cluster = Map.of("console", running("console", A),
                "queue-gate", running("queue-gate", A), "booking-service", running("booking-service", A));
        Versions.Version v = Versions.read(name -> Optional.ofNullable(cluster.get(name)));
        assertThat(v.commit()).isEqualTo(A);
        assertThat(v.mixed()).isFalse();
        assertThat(v.services()).extracting(Versions.Service::commit).containsOnly(A);
    }

    @Test
    void mixedWhileADeployIsHalfWayThrough() {
        Map<String, Deployment> cluster = Map.of("console", running("console", A),
                "queue-gate", running("queue-gate", B), "booking-service", running("booking-service", A));
        Versions.Version v = Versions.read(name -> Optional.ofNullable(cluster.get(name)));
        assertThat(v.mixed()).isTrue();
        assertThat(v.commit()).isNull();
    }

    @Test
    void aServiceThatCannotBeReadDoesNotMakeItMixed() {
        Map<String, Deployment> cluster = Map.of("console", running("console", A), "queue-gate", running("queue-gate", A));
        Versions.Version v = Versions.read(name -> {
            if (name.equals("booking-service")) {
                throw new IllegalStateException("forbidden");
            }
            return Optional.ofNullable(cluster.get(name));
        });
        assertThat(v.commit()).isEqualTo(A);
        assertThat(v.mixed()).isFalse();
        assertThat(v.services()).anyMatch(s -> s.name().equals("booking-service") && s.tag() == null);
    }

    @Test
    void aTagThatIsNotACommitIsReportedButNotLinked() {
        Map<String, Deployment> cluster = Map.of("console", running("console", "latest"));
        Versions.Version v = Versions.read(name -> Optional.ofNullable(cluster.get(name)));
        assertThat(v.services()).anyMatch(s -> s.name().equals("console") && "latest".equals(s.tag()) && s.commit() == null);
        assertThat(v.commit()).isNull();
    }
}
