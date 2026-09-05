package dev.marwan.console.cluster;

import dev.marwan.console.ConsoleProperties;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * One Kubernetes client for the whole console, built the first time something
 * asks for it.
 *
 * Three things now read the cluster — pods, the quota and autoscalers, and the
 * load Job — and each of them building its own client would open three
 * connections, hold three watches on a laptop with no API server at all, and
 * time out three times over on every poll. It is also the only place that
 * knows the timeouts, so a slow API server cannot hold a request thread per
 * viewer.
 *
 * <h2>Off-cluster is a normal state, not a failure</h2>
 * On a laptop there is no ServiceAccount token and the API may be unreachable.
 * The client is therefore built lazily and callers are expected to wrap their
 * calls, so the console starts, serves, and simply says it cannot see the
 * cluster. {@link #invalidate()} drops a client whose call failed, so the next
 * attempt reconnects rather than reusing a dead one.
 */
@Component
public class KubernetesAccess {

    private static final int API_TIMEOUT_MILLIS = 2000;

    private final ConsoleProperties properties;

    private volatile KubernetesClient client;

    public KubernetesAccess(ConsoleProperties properties) {
        this.properties = properties;
    }

    public String namespace() {
        return properties.namespace();
    }

    public KubernetesClient client() {
        KubernetesClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                Config config = new ConfigBuilder()
                        .withRequestTimeout(API_TIMEOUT_MILLIS)
                        .withConnectionTimeout(API_TIMEOUT_MILLIS)
                        .withNamespace(properties.namespace())
                        .build();
                client = new KubernetesClientBuilder().withConfig(config).build();
            }
            return client;
        }
    }

    /** Called after a failed call, so a dead client is not reused forever. */
    public void invalidate() {
        synchronized (this) {
            client = null;
        }
    }

    /**
     * A one-line version of whatever went wrong, short enough to sit in a card
     * on the page.
     *
     * Kubernetes API failures arrive as multi-line messages carrying the whole
     * request, and the useful part is the first clause.
     */
    public static String summarise(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
    }

    @PreDestroy
    void close() {
        KubernetesClient open = client;
        if (open != null) {
            open.close();
        }
    }
}
