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
public record PodHealth(boolean available, String detail, List<PodStatus> pods) {

    public static PodHealth of(List<PodStatus> pods) {
        return new PodHealth(true, null, pods);
    }

    public static PodHealth unavailable(String detail) {
        return new PodHealth(false, detail, List.of());
    }
}
