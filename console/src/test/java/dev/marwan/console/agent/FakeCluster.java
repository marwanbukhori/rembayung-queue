package dev.marwan.console.agent;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.objects.RouteProbe;

/** A namespace in memory: pods by app label, a log per pod, events per object. */
class FakeCluster implements ObjectSource {

    final List<Pod> pods = new ArrayList<>();
    final List<Job> jobs = new ArrayList<>();
    final Map<String, String> logs = new HashMap<>();
    final Map<String, List<Event>> events = new HashMap<>();
    final List<EndpointSlice> slices = new ArrayList<>();
    RuntimeException logFailure;

    static Pod pod(String name, String app, int restarts) {
        return new PodBuilder().withNewMetadata().withName(name).addToLabels("app", app).endMetadata()
                .withNewStatus().withPhase("Running")
                .withContainerStatuses(new ContainerStatusBuilder().withName("c").withReady(true)
                        .withRestartCount(restarts).build())
                .endStatus().build();
    }

    static Pod loadPod(String name, String job) {
        Pod p = pod(name, "rembayung-load", 0);
        p.getMetadata().getLabels().put("job-name", job);
        return p;
    }

    static Job loadJob(String name, String drop, Instant start, Instant done, boolean failed) {
        return new JobBuilder().withNewMetadata().withName(name)
                .addToLabels("app", "rembayung-load").addToLabels("rembayung.dev/drop", drop).endMetadata()
                .withNewStatus().withStartTime(start == null ? null : start.toString())
                .withCompletionTime(done == null || failed ? null : done.toString())
                .withSucceeded(done != null && !failed ? 1 : 0).withFailed(failed ? 1 : 0)
                .endStatus().build();
    }

    @Override public Optional<GenericKubernetesResource> route(String name) { return Optional.empty(); }
    @Override public Optional<Service> service(String name) { return Optional.empty(); }
    @Override public List<EndpointSlice> endpointSlices(String service) { return slices; }
    @Override public List<NetworkPolicy> networkPolicies() { return List.of(); }
    @Override public Optional<Deployment> deployment(String name) { return Optional.empty(); }
    @Override public List<ReplicaSet> replicaSets(String deployment) { return List.of(); }
    @Override public Optional<HorizontalPodAutoscaler> hpa(String name) { return Optional.empty(); }
    @Override public Optional<CronJob> cronJob(String name) { return Optional.empty(); }
    @Override public RouteProbe probe(String host) { return new RouteProbe(200, 5, true, null); }
    @Override public void reset() { }

    @Override
    public Optional<Pod> pod(String name) {
        return pods.stream().filter(p -> p.getMetadata().getName().equals(name)).findFirst();
    }

    @Override
    public List<Pod> pods(String app) {
        return pods.stream().filter(p -> app.equals(p.getMetadata().getLabels().get("app"))).toList();
    }

    @Override
    public Optional<Job> job(String name) {
        return jobs.stream().filter(j -> j.getMetadata().getName().equals(name)).findFirst();
    }

    @Override
    public List<Job> jobs() {
        return jobs;
    }

    @Override
    public List<Event> events(String kind, String name) {
        return events.getOrDefault(kind + "/" + name, List.of());
    }

    @Override
    public String podLog(String pod, int tailLines) {
        if (logFailure != null) {
            throw logFailure;
        }
        String log = logs.get(pod);
        if (log == null) {
            throw new IllegalStateException("pods \"" + pod + "\" not found");
        }
        return log;
    }
}
