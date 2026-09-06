package dev.marwan.console.cluster;

/**
 * One pod, in the terms the page shows.
 *
 * @param phase    Running, Succeeded, Pending or Failed, straight from the pod.
 *                 Readiness alone cannot tell a finished Job from a crash loop -
 *                 both report 0/1.
 * @param workload the Deployment or Job this pod belongs to, resolved from its
 *                 ownerReferences rather than by splitting the pod's name. A
 *                 name split is a guess that has been wrong twice on this
 *                 project: a Job pod has one random segment and a Deployment's
 *                 has two, and a drop id looks exactly like a ReplicaSet hash.
 * @param ownerKind ReplicaSet or Job, which is what makes the distinction above
 *                 decidable instead of guessable.
 * @param node     which node the scheduler placed it on.
 * @param podIp    the address the Service load-balances to.
 * @param qos      Guaranteed, Burstable or BestEffort - Kubernetes' own summary
 *                 of the requests and limits this project sets deliberately, and
 *                 the first thing an eviction decision looks at.
 * @param image    the image tag, so every pod being on one commit is visible.
 */
public record PodStatus(
        String name,
        String ready,
        boolean healthy,
        String cpu,
        int restarts,
        String age,
        String phase,
        String workload,
        String ownerKind,
        String node,
        String podIp,
        String qos,
        String image) { }
