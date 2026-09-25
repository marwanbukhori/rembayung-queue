package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class NetworkDescriberTest {

    /**
     * What "Connection refused" meant on 2026-09-25: a Service with nothing
     * behind it. The inspector must say that in the headline.
     */
    @Test
    void aServiceWithNoReadyEndpointsIsBad() {
        ObjectDetail detail = NetworkDescriber.service(service("booking-service"), List.of(), List.of());

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("0 ready endpoints - connections will be refused");
    }

    @Test
    void readyEndpointsAreCountedAndLinked() {
        EndpointSlice slice = new EndpointSliceBuilder().withNewMetadata().withName("booking-service-abc").endMetadata()
                .addNewEndpoint().withNewConditions().withReady(true).endConditions()
                    .withNewTargetRef().withKind("Pod").withName("booking-service-874f94d9-mfnlb").endTargetRef()
                .endEndpoint()
                .addNewEndpoint().withNewConditions().withReady(false).endConditions()
                    .withNewTargetRef().withKind("Pod").withName("booking-service-874f94d9-79ttd").endTargetRef()
                .endEndpoint()
                .build();

        ObjectDetail detail = NetworkDescriber.service(service("booking-service"), List.of(slice), List.of());

        assertThat(detail.tone()).isEqualTo(ObjectDetail.WARN);
        assertThat(detail.headline()).isEqualTo("1 of 2 endpoints ready");
        assertThat(detail.related()).extracting(ObjectDetail.Link::name)
                .contains("booking-service-874f94d9-mfnlb", "booking-service-874f94d9-79ttd");
    }

    @Test
    void aNetworkPolicySelectingTheServicesPodsIsNamedWithWhoItAdmits() {
        NetworkPolicy policy = new NetworkPolicyBuilder().withNewMetadata()
                .withName("booking-service-from-gate-only").endMetadata()
                .withNewSpec().withNewPodSelector().addToMatchLabels("app", "booking-service").endPodSelector()
                    .addNewIngress().addNewFrom().withNewPodSelector().addToMatchLabels("app", "queue-gate")
                    .endPodSelector().endFrom().endIngress()
                .endSpec().build();

        ObjectDetail detail = NetworkDescriber.service(service("booking-service"), List.of(), List.of(policy));

        assertThat(detail.facts()).extracting(ObjectDetail.Fact::label, ObjectDetail.Fact::value)
                .contains(tuple("NetworkPolicy",
                        "booking-service-from-gate-only: ingress from app=queue-gate only"));
    }

    @Test
    void aRouteWhoseAppAnswersIsOk() {
        ObjectDetail detail = NetworkDescriber.route(route("queue-gate"), new RouteProbe(404, 38, true, null));

        assertThat(detail.tone()).isEqualTo(ObjectDetail.OK);
        assertThat(detail.headline()).isEqualTo("app answering in 38 ms");
    }

    /** The router's own "Application is not available" page is not the app answering. */
    @Test
    void aRouteAnsweredOnlyByTheRouterIsBad() {
        ObjectDetail detail = NetworkDescriber.route(route("queue-gate"), new RouteProbe(503, 12, false, null));

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("503 from the router - nothing behind it");
    }

    @Test
    void aRouteThatDoesNotAnswerAtAllSaysSo() {
        ObjectDetail detail = NetworkDescriber.route(route("queue-gate"), RouteProbe.failed("timed out after 3s"));

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("no answer: timed out after 3s");
    }

    private static Service service(String name) {
        return new ServiceBuilder().withNewMetadata().withName(name).endMetadata()
                .withNewSpec().addToSelector("app", name)
                    .addNewPort().withName("http").withPort(8081).endPort()
                .endSpec().build();
    }

    private static GenericKubernetesResource route(String name) {
        GenericKubernetesResource route = new GenericKubernetesResource();
        route.setMetadata(new ObjectMetaBuilder().withName(name).build());
        route.setAdditionalProperty("spec", Map.of(
                "host", name + "-marwanbukhori-dev.apps.example.com",
                "to", Map.of("kind", "Service", "name", name),
                "port", Map.of("targetPort", "http"),
                "tls", Map.of("termination", "edge", "insecureEdgeTerminationPolicy", "Redirect")));
        return route;
    }
}
