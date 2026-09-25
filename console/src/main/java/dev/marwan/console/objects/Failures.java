package dev.marwan.console.objects;

import io.fabric8.kubernetes.client.KubernetesClientException;

/** Which failures mean the shared client is broken, as opposed to the API answering no. */
final class Failures {

    private Failures() {
    }

    /**
     * A 4xx is the API server answering over a working connection - a 403
     * before RBAC is applied, a 404 for a pod just replaced, a 400 for a
     * container not started. Dropping the shared client for it would churn the
     * client every other panel uses, for nothing.
     */
    static boolean resetsTheClient(Throwable e) {
        return !(e instanceof KubernetesClientException k && k.getCode() >= 400 && k.getCode() < 500);
    }
}
