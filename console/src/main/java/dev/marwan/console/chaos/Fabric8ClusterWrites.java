package dev.marwan.console.chaos;

import java.time.Instant;

import org.springframework.stereotype.Component;

import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;

/**
 * ClusterWrites through fabric8, as merge patches carrying only the fields that
 * change - never a whole object read back from the API, which fabric8 cannot
 * re-serialise (note 12) and which would also overwrite anything else that moved.
 */
@Component
public class Fabric8ClusterWrites implements ClusterWrites {

    private static final PatchContext MERGE = PatchContext.of(PatchType.JSON_MERGE);

    private final KubernetesAccess kubernetes;

    public Fabric8ClusterWrites(KubernetesAccess kubernetes) {
        this.kubernetes = kubernetes;
    }

    @Override
    public void deletePod(String name) {
        kubernetes.client().pods().inNamespace(kubernetes.namespace()).withName(name).delete();
    }

    @Override
    public void scale(String deployment, int replicas) {
        kubernetes.client().apps().deployments().inNamespace(kubernetes.namespace()).withName(deployment)
                .scale(replicas);
    }

    @Override
    public void restart(String deployment) {
        kubernetes.client().apps().deployments().inNamespace(kubernetes.namespace()).withName(deployment)
                .patch(MERGE, "{\"spec\":{\"template\":{\"metadata\":{\"annotations\":"
                        + "{\"kubectl.kubernetes.io/restartedAt\":\"" + Instant.now() + "\"}}}}}");
    }

    @Override
    public void setHpaMin(String hpa, int minReplicas) {
        kubernetes.client().autoscaling().v2().horizontalPodAutoscalers().inNamespace(kubernetes.namespace())
                .withName(hpa).patch(MERGE, "{\"spec\":{\"minReplicas\":" + minReplicas + "}}");
    }

    @Override
    public int hpaMin(String hpa) {
        var h = kubernetes.client().autoscaling().v2().horizontalPodAutoscalers().inNamespace(kubernetes.namespace())
                .withName(hpa).get();
        return h == null || h.getSpec().getMinReplicas() == null ? 1 : h.getSpec().getMinReplicas();
    }
}
