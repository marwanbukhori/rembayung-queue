package dev.marwan.console.objects;

import java.util.Locale;
import java.util.Optional;

/**
 * The kinds the inspector can describe, by the name each carries in a URL.
 *
 * A closed set on purpose. The kind comes from a public URL, and an open
 * mapping to Kubernetes kinds is one typo away from describing Secrets.
 */
public enum ObjectKind {
    ROUTE, SERVICE, DEPLOYMENT, POD, HPA, JOB, CRONJOB;

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ObjectKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (ObjectKind kind : values()) {
            if (kind.path().equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
