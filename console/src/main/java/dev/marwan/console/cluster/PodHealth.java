package dev.marwan.console.cluster;

import java.util.List;

/**
 * Pod health, or the reason there is none.
 *
 * Running outside a cluster is the normal case on a laptop, and the Kubernetes
 * API being briefly unavailable is the normal case inside one. Neither is an
 * error the console should propagate: the page keeps its other sections and
 * prints the reason where the pod list would be.
 */
public record PodHealth(boolean available, String detail, String namespace, List<PodStatus> pods) {

    /**
     * The namespace is reported, not assumed by the caller.
     *
     * The header previously printed a hardcoded "ns/rembayung" while the backend
     * read marwanbukhori-dev — a label confidently naming a namespace that does
     * not exist. Anything the page says about the cluster should come from the
     * cluster.
     */
    public static PodHealth of(String namespace, List<PodStatus> pods) {
        return new PodHealth(true, null, namespace, pods);
    }

    public static PodHealth unavailable(String detail) {
        return new PodHealth(false, detail, null, List.of());
    }
}
