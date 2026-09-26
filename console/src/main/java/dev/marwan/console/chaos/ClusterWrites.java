package dev.marwan.console.chaos;

/**
 * The only changes the console makes to workloads: the ones a drill or an
 * approved fix needs, and nothing else. An interface so the logic that decides
 * is tested without a cluster.
 */
public interface ClusterWrites {

    void deletePod(String name);

    void scale(String deployment, int replicas);

    /** A rolling restart, the way `oc rollout restart` does it: a new annotation on the pod template. */
    void restart(String deployment);

    void setHpaMin(String hpa, int minReplicas);

    int hpaMin(String hpa);
}
