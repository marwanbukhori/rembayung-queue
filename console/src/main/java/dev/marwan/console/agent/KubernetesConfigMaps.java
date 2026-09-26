package dev.marwan.console.agent;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClientException;

import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.marwan.console.cluster.KubernetesAccess;

/**
 * The ConfigMaps the console keeps its own state in - reports, the chaos lock,
 * incidents - through fabric8. Writes are always of a clean object built by the
 * caller; a 409 from the API becomes {@link AnalysisStore.Conflict}.
 */
@Component
public class KubernetesConfigMaps implements AnalysisStore.ConfigMapPort {

    private final KubernetesAccess kubernetes;

    public KubernetesConfigMaps(KubernetesAccess kubernetes) {
        this.kubernetes = kubernetes;
    }

    @Override
    public Optional<ConfigMap> get(String name) {
        return Optional.ofNullable(kubernetes.client().configMaps().inNamespace(kubernetes.namespace())
                .withName(name).get());
    }

    @Override
    public void create(ConfigMap map) {
        try {
            kubernetes.client().configMaps().inNamespace(kubernetes.namespace()).resource(map).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                throw new AnalysisStore.Conflict();
            }
            throw e;
        }
    }

    @Override
    public void update(ConfigMap map) {
        try {
            kubernetes.client().configMaps().inNamespace(kubernetes.namespace()).resource(map).update();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                throw new AnalysisStore.Conflict();
            }
            throw e;
        }
    }
}
