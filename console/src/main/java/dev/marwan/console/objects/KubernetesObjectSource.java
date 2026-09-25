package dev.marwan.console.objects;

import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** {@link ObjectSource} over the console's one Kubernetes client. */
@Component
class KubernetesObjectSource implements ObjectSource {

    /** Review focus 5: a hanging host must not hold a request thread. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private static final ResourceDefinitionContext ROUTES = new ResourceDefinitionContext.Builder()
            .withGroup("route.openshift.io").withVersion("v1").withPlural("routes").withNamespaced(true).build();

    private final KubernetesAccess kubernetes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    KubernetesObjectSource(KubernetesAccess kubernetes) {
        this.kubernetes = kubernetes;
    }

    private String ns() {
        return kubernetes.namespace();
    }

    @Override
    public Optional<GenericKubernetesResource> route(String name) {
        return Optional.ofNullable(kubernetes.client().genericKubernetesResources(ROUTES)
                .inNamespace(ns()).withName(name).get());
    }

    @Override
    public Optional<Service> service(String name) {
        return Optional.ofNullable(kubernetes.client().services().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<EndpointSlice> endpointSlices(String service) {
        return kubernetes.client().discovery().v1().endpointSlices().inNamespace(ns())
                .withLabel("kubernetes.io/service-name", service).list().getItems();
    }

    @Override
    public List<NetworkPolicy> networkPolicies() {
        return kubernetes.client().network().v1().networkPolicies().inNamespace(ns()).list().getItems();
    }

    @Override
    public Optional<Deployment> deployment(String name) {
        return Optional.ofNullable(kubernetes.client().apps().deployments().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<ReplicaSet> replicaSets(String deployment) {
        return kubernetes.client().apps().replicaSets().inNamespace(ns()).withLabel("app", deployment)
                .list().getItems().stream()
                .filter(rs -> rs.getMetadata().getOwnerReferences() != null && rs.getMetadata().getOwnerReferences()
                        .stream().anyMatch(o -> "Deployment".equals(o.getKind()) && deployment.equals(o.getName())))
                .toList();
    }

    @Override
    public Optional<Pod> pod(String name) {
        return Optional.ofNullable(kubernetes.client().pods().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<Pod> pods(String appLabel) {
        return kubernetes.client().pods().inNamespace(ns()).withLabel("app", appLabel).list().getItems();
    }

    @Override
    public Optional<HorizontalPodAutoscaler> hpa(String name) {
        return Optional.ofNullable(kubernetes.client().autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(ns()).withName(name).get());
    }

    @Override
    public Optional<Job> job(String name) {
        return Optional.ofNullable(kubernetes.client().batch().v1().jobs().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<Job> jobs() {
        return kubernetes.client().batch().v1().jobs().inNamespace(ns()).list().getItems();
    }

    @Override
    public Optional<CronJob> cronJob(String name) {
        return Optional.ofNullable(kubernetes.client().batch().v1().cronjobs().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<Event> events(String kind, String name) {
        return kubernetes.client().v1().events().inNamespace(ns())
                .withField("involvedObject.kind", kind)
                .withField("involvedObject.name", name)
                .list().getItems();
    }

    @Override
    public String podLog(String pod, int tailLines) {
        // usingTimestamps puts the kubelet's own time on every line, JSON or not,
        // which is what the page's cursor needs: redis prints plain text.
        //
        // No limitBytes. The API counts it from the start of the tail, so a byte
        // cap dropped the newest lines - and cut the last one in half - during
        // exactly the error bursts someone is watching. The tail bounds the read;
        // LogLines cuts each message.
        return kubernetes.client().pods().inNamespace(ns()).withName(pod)
                .usingTimestamps().tailingLines(tailLines).getLog();
    }

    @Override
    public RouteProbe probe(String host) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("https://" + host + "/")).timeout(PROBE_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            long millis = (System.nanoTime() - start) / 1_000_000;
            String body = response.body() == null ? "" : response.body().stripLeading();
            // Our apps answer in JSON (queue-gate) or serve the console page; the
            // router's "Application is not available" page is neither.
            boolean app = response.statusCode() < 500
                    && (body.startsWith("{") || body.contains("<title>Rembayung"));
            return new RouteProbe(response.statusCode(), millis, app, null);
        } catch (HttpTimeoutException e) {
            return RouteProbe.failed("timed out after " + PROBE_TIMEOUT.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RouteProbe.failed("interrupted");
        } catch (Exception e) {
            return RouteProbe.failed(KubernetesAccess.summarise(e));
        }
    }

    @Override
    public void reset() {
        kubernetes.invalidate();
    }
}
