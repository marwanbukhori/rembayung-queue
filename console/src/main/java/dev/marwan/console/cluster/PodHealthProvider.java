package dev.marwan.console.cluster;

import dev.marwan.console.ConsoleProperties;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pods in this namespace, read through the Kubernetes API.
 *
 * The ServiceAccount task 8 mounts is scoped to one namespace and cannot read
 * Secrets, so the worst this code can do is describe workloads that are already
 * described on the OpenShift console. That is deliberate: the page is public,
 * and a public page should only be able to see what a screenshot of the cluster
 * would show anyway.
 *
 * <h2>Off-cluster is a normal state, not a failure</h2>
 * On a laptop there is no ServiceAccount token and the API is unreachable. The
 * client comes from {@link KubernetesAccess}, which builds it lazily, and every
 * call is wrapped, so the console starts, serves, and simply says it cannot see
 * the cluster. A failure is remembered briefly rather than retried on every
 * poll, because a DNS lookup that will not resolve should not be attempted once
 * per viewer per second.
 */
@Service
public class PodHealthProvider {

    private static final Logger log = LoggerFactory.getLogger(PodHealthProvider.class);

    /** Long enough that a dead API is not dialled every poll, short enough that a recovery shows up. */
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(30);

    private final ConsoleProperties properties;
    private final KubernetesAccess kubernetes;
    private final Clock clock;

    private volatile Instant lastFailure;
    private volatile String lastFailureDetail;
    private volatile Cached cached;

    public PodHealthProvider(ConsoleProperties properties, KubernetesAccess kubernetes, Clock clock) {
        this.properties = properties;
        this.kubernetes = kubernetes;
        this.clock = clock;
    }

    public PodHealth current() {
        Instant now = clock.instant();
        Cached hit = cached;
        if (hit != null && Duration.between(hit.at(), now).compareTo(properties.cacheTtl()) < 0) {
            return hit.health();
        }
        if (lastFailure != null && Duration.between(lastFailure, now).compareTo(RETRY_AFTER_FAILURE) < 0) {
            return PodHealth.unavailable(lastFailureDetail);
        }
        PodHealth fresh = read();
        cached = new Cached(now, fresh);
        return fresh;
    }

    private PodHealth read() {
        try {
            List<PodStatus> pods = kubernetes.client().pods()
                    .inNamespace(properties.namespace())
                    .list()
                    .getItems()
                    .stream()
                    .map(this::describe)
                    .sorted(Comparator.comparing(PodStatus::name))
                    .toList();
            lastFailure = null;
            lastFailureDetail = null;
            return PodHealth.of(pods);
        } catch (Throwable e) {
            // Throwable rather than Exception: a missing optional HTTP client on
            // the classpath surfaces as an Error, and the console going down
            // because it could not describe pods would be the wrong trade.
            String detail = "cluster not readable: " + KubernetesAccess.summarise(e);
            log.warn("console could not read pods in {}: {}", properties.namespace(), e.toString());
            lastFailure = clock.instant();
            lastFailureDetail = detail;
            kubernetes.invalidate();
            return PodHealth.unavailable(detail);
        }
    }

    private PodStatus describe(Pod pod) {
        List<ContainerStatus> containers = pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null
                ? List.of()
                : pod.getStatus().getContainerStatuses();
        long ready = containers.stream().filter(c -> Boolean.TRUE.equals(c.getReady())).count();
        int restarts = containers.stream()
                .map(ContainerStatus::getRestartCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        return new PodStatus(
                pod.getMetadata().getName(),
                ready + "/" + containers.size(),
                !containers.isEmpty() && ready == containers.size(),
                cpuRequest(pod),
                restarts,
                age(pod));
    }

    /** What the pod asked the scheduler for, which is what the namespace budget is spent on. */
    private String cpuRequest(Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return "-";
        }
        BigDecimal cores = pod.getSpec().getContainers().stream()
                .filter(c -> c.getResources() != null && c.getResources().getRequests() != null)
                .map(c -> c.getResources().getRequests().get("cpu"))
                .filter(Objects::nonNull)
                .map(Quantity::getAmountInBytes)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return cores.movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP) + "m";
    }

    private String age(Pod pod) {
        String created = pod.getMetadata() == null ? null : pod.getMetadata().getCreationTimestamp();
        if (created == null) {
            return "-";
        }
        try {
            Duration up = Duration.between(Instant.parse(created), clock.instant());
            if (up.toDays() > 0) {
                return up.toDays() + "d";
            }
            if (up.toHours() > 0) {
                return up.toHours() + "h";
            }
            return Math.max(0, up.toMinutes()) + "m";
        } catch (Exception e) {
            return "-";
        }
    }

    private record Cached(Instant at, PodHealth health) { }
}
