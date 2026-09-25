package dev.marwan.console.objects;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** An in-memory cluster. Counts reads so caching can be observed. */
class FakeObjectSource implements ObjectSource {

    final List<Pod> pods = new ArrayList<>();
    final List<Job> jobs = new ArrayList<>();
    RuntimeException failWith;
    /** Runs on every read, so a test can make a read take time on the provider's clock. */
    Runnable onRead = () -> { };
    int reads;
    int resets;
    String log = "";
    RuntimeException failLogWith;
    int logReads;
    int lastTail;

    @Override public Optional<GenericKubernetesResource> route(String name) { read(); return Optional.empty(); }
    @Override public Optional<Service> service(String name) { read(); return Optional.empty(); }
    @Override public List<EndpointSlice> endpointSlices(String service) { read(); return List.of(); }
    @Override public List<NetworkPolicy> networkPolicies() { read(); return List.of(); }
    Deployment deployment;

    @Override public Optional<Deployment> deployment(String name) { read(); return Optional.ofNullable(deployment); }
    @Override public List<ReplicaSet> replicaSets(String deployment) { read(); return List.of(); }
    @Override public Optional<HorizontalPodAutoscaler> hpa(String name) { read(); return Optional.empty(); }
    @Override public Optional<CronJob> cronJob(String name) { read(); return Optional.empty(); }
    @Override public List<Event> events(String kind, String name) { read(); return List.of(); }
    @Override public RouteProbe probe(String host) { return new RouteProbe(200, 5, true, null); }
    @Override public void reset() { resets++; }

    @Override
    public Optional<Pod> pod(String name) {
        read();
        return pods.stream().filter(p -> p.getMetadata().getName().equals(name)).findFirst();
    }

    @Override
    public List<Pod> pods(String appLabel) {
        read();
        return pods;
    }

    @Override
    public Optional<Job> job(String name) {
        read();
        return jobs.stream().filter(j -> j.getMetadata().getName().equals(name)).findFirst();
    }

    @Override
    public List<Job> jobs() {
        read();
        return jobs;
    }

    @Override
    public String podLog(String pod, int tailLines) {
        read();
        logReads++;
        lastTail = tailLines;
        if (failLogWith != null) {
            throw failLogWith;
        }
        return log;
    }

    private void read() {
        reads++;
        onRead.run();
        if (failWith != null) {
            throw failWith;
        }
    }
}
