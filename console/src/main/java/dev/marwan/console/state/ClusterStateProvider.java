package dev.marwan.console.state;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerCondition;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The constraints panel's numbers, read from the Kubernetes API.
 *
 * <h2>Two seconds of cache</h2>
 * Short enough that a Job going Pending shows up while the visitor is still
 * looking at the button, long enough that any number of people with the page
 * open cost the API server one list per two seconds rather than one per viewer
 * per poll. The console is what someone opens when the cluster is unhappy; it
 * must not be part of why.
 *
 * <h2>It never throws</h2>
 * Every read is wrapped and returns {@link ClusterState#unavailable(String)}
 * with the reason. Off-cluster this is the permanent state, and the page is
 * expected to render it as a sentence rather than an error.
 */
@Service
public class ClusterStateProvider {

    private static final Logger log = LoggerFactory.getLogger(ClusterStateProvider.class);

    /** Named in the task: fresh enough to watch, cheap enough to share. */
    private static final Duration CACHE = Duration.ofSeconds(2);

    /** ReplicaSet names are the Deployment plus a pod-template hash. */
    private static final Pattern REPLICASET_SUFFIX = Pattern.compile("-[a-z0-9]{6,10}$");

    /** Pods in these phases have released their request back to the quota. */
    private static final List<String> SETTLED = List.of("Succeeded", "Failed");

    private final ConsoleProperties properties;
    private final KubernetesAccess kubernetes;
    private final Clock clock;

    private volatile Cached cached;

    public ClusterStateProvider(ConsoleProperties properties, KubernetesAccess kubernetes, Clock clock) {
        this.properties = properties;
        this.kubernetes = kubernetes;
        this.clock = clock;
    }

    public ClusterState current() {
        Instant now = clock.instant();
        Cached hit = cached;
        if (hit != null && Duration.between(hit.at(), now).compareTo(CACHE) < 0) {
            return hit.state();
        }
        ClusterState fresh = read();
        cached = new Cached(now, fresh);
        return fresh;
    }

    private ClusterState read() {
        try {
            List<Pod> pods = kubernetes.client().pods()
                    .inNamespace(properties.namespace())
                    .list().getItems();
            List<HorizontalPodAutoscaler> hpas = kubernetes.client().autoscaling().v2()
                    .horizontalPodAutoscalers()
                    .inNamespace(properties.namespace())
                    .list().getItems();
            ResourceQuota quota = kubernetes.client().resourceQuotas()
                    .inNamespace(properties.namespace())
                    .withName(properties.quota())
                    .get();
            if (quota == null) {
                return ClusterState.unavailable(
                        "no ResourceQuota named " + properties.quota() + " in " + properties.namespace());
            }
            return ClusterState.of(
                    quotaOf(quota),
                    consumers(pods),
                    hpas.stream().map(this::describe).toList(),
                    pool(hpas));
        } catch (Throwable e) {
            // Throwable rather than Exception: a missing optional HTTP client on
            // the classpath surfaces as an Error, and the console going down
            // because it could not describe a quota would be the wrong trade.
            String detail = "cluster not readable: " + KubernetesAccess.summarise(e);
            log.warn("console could not read the constraints in {}: {}", properties.namespace(), e.toString());
            kubernetes.invalidate();
            return ClusterState.unavailable(detail);
        }
    }

    private ClusterState.Quota quotaOf(ResourceQuota quota) {
        Map<String, Quantity> used = quota.getStatus() == null || quota.getStatus().getUsed() == null
                ? Map.of() : quota.getStatus().getUsed();
        Map<String, Quantity> hard = quota.getStatus() == null || quota.getStatus().getHard() == null
                ? Map.of() : quota.getStatus().getHard();
        return new ClusterState.Quota(
                quota.getMetadata().getName(),
                millis(used.get("requests.cpu")),
                millis(hard.get("requests.cpu")));
    }

    /**
     * What is spending the budget, grouped by the workload that owns the pods.
     *
     * Pods that have finished are left out: they have already given their
     * request back, and a list of yesterday's keepalive Jobs above the running
     * workloads would explain nothing about why a run cannot schedule now.
     */
    private List<ClusterState.Consumer> consumers(List<Pod> pods) {
        Map<String, int[]> byOwner = new LinkedHashMap<>();
        for (Pod pod : pods) {
            String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
            if (phase != null && SETTLED.contains(phase)) {
                continue;
            }
            int[] tally = byOwner.computeIfAbsent(workloadOf(pod), key -> new int[2]);
            tally[0] += cpuRequestMillis(pod);
            tally[1] += 1;
        }
        List<ClusterState.Consumer> consumers = new ArrayList<>(byOwner.entrySet().stream()
                .map(e -> new ClusterState.Consumer(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingInt(ClusterState.Consumer::millis).reversed())
                .toList());
        return consumers;
    }

    /**
     * The Deployment or Job behind a pod.
     *
     * A pod's own name carries two random suffixes and tells a reader nothing,
     * so this walks up to the ReplicaSet and strips the pod-template hash. A
     * load Job is owned directly by the Job, whose name already says which drop
     * it belongs to — which is exactly what a visitor needs to see in this list.
     */
    private String workloadOf(Pod pod) {
        List<OwnerReference> owners = pod.getMetadata() == null ? null : pod.getMetadata().getOwnerReferences();
        if (owners == null || owners.isEmpty()) {
            return pod.getMetadata() == null ? "unnamed pod" : pod.getMetadata().getName();
        }
        OwnerReference owner = owners.getFirst();
        if ("ReplicaSet".equals(owner.getKind())) {
            return REPLICASET_SUFFIX.matcher(owner.getName()).replaceFirst("");
        }
        return owner.getName();
    }

    private int cpuRequestMillis(Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return 0;
        }
        return pod.getSpec().getContainers().stream()
                .filter(c -> c.getResources() != null && c.getResources().getRequests() != null)
                .map(c -> c.getResources().getRequests().get("cpu"))
                .filter(Objects::nonNull)
                .mapToInt(ClusterStateProvider::millis)
                .sum();
    }

    private ClusterState.Autoscaler describe(HorizontalPodAutoscaler hpa) {
        int current = value(hpa.getStatus() == null ? null : hpa.getStatus().getCurrentReplicas());
        int desired = value(hpa.getStatus() == null ? null : hpa.getStatus().getDesiredReplicas());
        return new ClusterState.Autoscaler(
                "hpa/" + hpa.getMetadata().getName(),
                current,
                desired,
                value(hpa.getSpec() == null ? null : hpa.getSpec().getMinReplicas()),
                hpa.getSpec() == null ? 0 : hpa.getSpec().getMaxReplicas(),
                observedUtilisation(hpa),
                targetUtilisation(hpa),
                limitNote(hpa));
    }

    private Integer targetUtilisation(HorizontalPodAutoscaler hpa) {
        if (hpa.getSpec() == null || hpa.getSpec().getMetrics() == null) {
            return null;
        }
        return hpa.getSpec().getMetrics().stream()
                .map(MetricSpec::getResource)
                .filter(Objects::nonNull)
                .filter(r -> "cpu".equals(r.getName()) && r.getTarget() != null)
                .map(r -> r.getTarget().getAverageUtilization())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Integer observedUtilisation(HorizontalPodAutoscaler hpa) {
        if (hpa.getStatus() == null || hpa.getStatus().getCurrentMetrics() == null) {
            return null;
        }
        return hpa.getStatus().getCurrentMetrics().stream()
                .map(MetricStatus::getResource)
                .filter(Objects::nonNull)
                .filter(r -> "cpu".equals(r.getName()) && r.getCurrent() != null)
                .map(r -> r.getCurrent().getAverageUtilization())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * The autoscaler's own explanation of why it is not moving, verbatim.
     *
     * "the desired replica count is less than the minimum replica count" is a
     * sentence Kubernetes wrote about this cluster at this moment. Anything the
     * console invented in its place would be a paraphrase of something it did
     * not observe.
     */
    private String limitNote(HorizontalPodAutoscaler hpa) {
        if (hpa.getStatus() == null || hpa.getStatus().getConditions() == null) {
            return null;
        }
        return hpa.getStatus().getConditions().stream()
                .filter(c -> "ScalingLimited".equals(c.getType()) && "True".equals(c.getStatus()))
                .map(HorizontalPodAutoscalerCondition::getMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Replicas times the pool size, against Oracle's session cap.
     *
     * The replica count comes from the booking-service HPA rather than from the
     * Deployment, because the HPA is what actually moves it and is already
     * being read here.
     */
    private ClusterState.Pool pool(List<HorizontalPodAutoscaler> hpas) {
        ConsoleProperties.Pool configured = properties.pool();
        int replicas = hpas.stream()
                .filter(h -> configured.deployment().equals(name(h)))
                .map(h -> value(h.getStatus() == null ? null : h.getStatus().getCurrentReplicas()))
                .findFirst()
                .orElse(0);
        return new ClusterState.Pool(configured.deployment(), replicas,
                configured.perReplica(), configured.cap());
    }

    private static String name(HasMetadata resource) {
        return resource.getMetadata() == null ? null : resource.getMetadata().getName();
    }

    private static int value(Integer maybe) {
        return maybe == null ? 0 : maybe;
    }

    /**
     * A CPU {@link Quantity} in millicores.
     *
     * fabric8 normalises "3", "3000m" and "3.0" to the same amount in base
     * units, so this multiplies rather than parsing suffixes — which is what
     * makes {@code requests.cpu: 3} and {@code requests.cpu: 3000m} report the
     * same budget, as they must, since they are the same budget.
     */
    private static int millis(Quantity quantity) {
        if (quantity == null) {
            return 0;
        }
        BigDecimal cores = Quantity.getAmountInBytes(quantity);
        return cores == null ? 0 : cores.movePointRight(3).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private record Cached(Instant at, ClusterState state) { }
}
