package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether an object belongs to this project, and so may be described.
 *
 * The namespace is ours, but not everything in it is: `oc debug` pods and
 * anything a person creates by hand live there too. Three ways in, each
 * matching how that kind of object is actually labelled here:
 * kustomize puts part-of on every manifest but not on pod templates; the four
 * workloads and load runs share an `app` label; keepalive Jobs and their pods
 * carry nothing but their owner.
 */
final class Scope {

    static final String PART_OF = "app.kubernetes.io/part-of";
    static final String PROJECT = "rembayung-queue";
    static final Set<String> APPS =
            Set.of("console", "queue-gate", "booking-service", "redis", "rembayung-load");

    private Scope() {
    }

    static boolean ours(HasMetadata object) {
        ObjectMeta meta = object == null ? null : object.getMetadata();
        if (meta == null) {
            return false;
        }
        Map<String, String> labels = meta.getLabels() == null ? Map.of() : meta.getLabels();
        // Set.of rejects null lookups, and an unlabelled object has no app label.
        String app = labels.get("app");
        if (PROJECT.equals(labels.get(PART_OF)) || (app != null && APPS.contains(app))) {
            return true;
        }
        List<OwnerReference> owners = meta.getOwnerReferences() == null ? List.of() : meta.getOwnerReferences();
        return owners.stream().anyMatch(o ->
                ("CronJob".equals(o.getKind()) && "keepalive".equals(o.getName()))
                        || ("Job".equals(o.getKind()) && o.getName() != null
                            && o.getName().startsWith("keepalive-")));
    }
}
