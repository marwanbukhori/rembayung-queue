package dev.marwan.console.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import io.fabric8.kubernetes.api.model.apps.Deployment;

/**
 * What is running, read from the Deployments rather than baked into the build.
 *
 * CI tags every image with its full commit SHA and CD deploys exactly that tag,
 * so the tag in each Deployment is the commit that service runs. Reading all
 * three means the badge can say when they differ - half-way through a deploy,
 * or after a rollback of one service - instead of vouching for the console alone.
 */
public final class Versions {

    static final List<String> SERVICES = List.of("console", "queue-gate", "booking-service");

    public record Service(String name, String tag, String commit) { }

    /** {@code commit} is set only when every service that could be read runs the same one. */
    public record Version(String commit, boolean mixed, List<Service> services) { }

    private Versions() { }

    public static Version read(Function<String, Optional<Deployment>> deployments) {
        List<Service> services = new ArrayList<>();
        for (String name : SERVICES) {
            String tag = null;
            try {
                tag = deployments.apply(name).map(Versions::tag).orElse(null);
            } catch (RuntimeException e) {
                // Unreadable: reported as unknown, and it does not make the others "mixed".
            }
            services.add(new Service(name, tag, tag != null && tag.matches("[0-9a-f]{40}") ? tag : null));
        }
        List<String> tags = services.stream().map(Service::tag).filter(t -> t != null).distinct().toList();
        boolean mixed = tags.size() > 1;
        String commit = !mixed && tags.size() == 1 ? services.stream().map(Service::commit)
                .filter(c -> c != null).findFirst().orElse(null) : null;
        return new Version(commit, mixed, services);
    }

    private static String tag(Deployment d) {
        if (d.getSpec() == null || d.getSpec().getTemplate() == null || d.getSpec().getTemplate().getSpec() == null
                || d.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
            return null;
        }
        String image = d.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
        int colon = image == null ? -1 : image.lastIndexOf(':');
        return colon < 0 || image.lastIndexOf('/') > colon ? null : image.substring(colon + 1);
    }
}
