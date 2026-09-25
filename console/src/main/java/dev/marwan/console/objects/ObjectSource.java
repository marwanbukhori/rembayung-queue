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

import java.util.List;
import java.util.Optional;

/**
 * Every read the inspector makes, in this namespace. An interface so the
 * provider's rules are tested against an in-memory cluster.
 */
public interface ObjectSource {

    Optional<GenericKubernetesResource> route(String name);

    Optional<Service> service(String name);

    List<EndpointSlice> endpointSlices(String service);

    List<NetworkPolicy> networkPolicies();

    Optional<Deployment> deployment(String name);

    List<ReplicaSet> replicaSets(String deployment);

    Optional<Pod> pod(String name);

    List<Pod> pods(String appLabel);

    Optional<HorizontalPodAutoscaler> hpa(String name);

    Optional<Job> job(String name);

    List<Job> jobs();

    Optional<CronJob> cronJob(String name);

    List<Event> events(String kind, String name);

    RouteProbe probe(String host);

    /** Drop a client whose call failed. */
    void reset();
}
