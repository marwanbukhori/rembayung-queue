package dev.marwan.console.web;

import dev.marwan.console.state.ClusterState;
import dev.marwan.console.state.ClusterStateProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The constraints panel's endpoint: the namespace CPU budget, what is spending
 * it, the autoscalers and the connection pool.
 *
 * Separate from {@code /api/state} rather than folded into it, because the two
 * answer different questions on different clocks. The drop's numbers come from
 * two HTTP calls to services this project wrote and are cached for a second;
 * these come from three Kubernetes API lists and are cached for two. Merging
 * them would tie a slow API server to the panel that has to keep working when
 * the API server is slow — and the reason for splitting availability per panel
 * in the first place was so that one unreadable thing does not blank the rest.
 *
 * Like every read here it answers 200 with a reason inside, never a 500. Behind
 * the console key; see KeyFilter.
 */
@RestController
public class ClusterController {

    private final ClusterStateProvider cluster;

    public ClusterController(ClusterStateProvider cluster) {
        this.cluster = cluster;
    }

    @GetMapping("/api/cluster")
    public ClusterState cluster() {
        return cluster.current();
    }
}
