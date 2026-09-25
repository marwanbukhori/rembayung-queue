package dev.marwan.console.objects;

import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One object's description, for the inspector.
 *
 * Three rules sit here rather than in the describers: only this project's
 * objects are described (anything else is a 404, however it was named); a
 * reading is reused for two seconds, so ten people watching a rush cost the
 * API server what one does; and an unreadable cluster is a sentence, never an
 * exception, as it is everywhere else in this console.
 */
@Component
public class ObjectsProvider {

    static final Duration TTL = Duration.ofSeconds(2);
    static final int RECENT_JOBS = 20;
    /**
     * Names arrive in a public URL, so the cache is bounded: expired entries
     * are dropped once it passes this size, and if a flood of fresh names still
     * fills it, it is emptied rather than allowed to grow.
     */
    static final int MAX_ENTRIES = 256;

    private static final Logger log = LoggerFactory.getLogger(ObjectsProvider.class);

    private final ObjectSource source;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile CachedJobs jobsCache;

    /** @param detail null when the object was not found, so repeated 404s are cheap too */
    private record Cached(ObjectDetail detail, String notFound, Instant at) { }

    private record CachedJobs(List<ObjectSummary> jobs, Instant at) { }

    public ObjectsProvider(ObjectSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    public ObjectDetail describe(String kindName, String name) {
        ObjectKind kind = ObjectKind.parse(kindName)
                .orElseThrow(() -> new ObjectNotFound("no such kind: " + kindName));
        String key = kind.path() + "/" + name;
        Cached held = cache.get(key);
        if (held != null && held.at().plus(TTL).isAfter(clock.instant())) {
            if (held.detail() == null) {
                throw new ObjectNotFound(held.notFound());
            }
            return held.detail();
        }
        ObjectDetail fresh;
        try {
            fresh = read(kind, name, clock.instant());
        } catch (ObjectNotFound e) {
            remember(key, new Cached(null, e.getMessage(), clock.instant()));
            throw e;
        } catch (Throwable e) {
            if (Failures.resetsTheClient(e)) {
                source.reset();
            }
            log.debug("could not describe {}: {}", key, e.toString());
            fresh = ObjectDetail.unavailable(kind, name, KubernetesAccess.summarise(e));
        }
        // Stamped when the read finished, not when it began: a read that took
        // the Route probe's full 3s would otherwise be stored already expired.
        remember(key, new Cached(fresh, null, clock.instant()));
        return fresh;
    }

    int cachedEntries() {
        return cache.size();
    }

    private void remember(String key, Cached entry) {
        if (cache.size() >= MAX_ENTRIES) {
            Instant now = clock.instant();
            cache.values().removeIf(c -> !c.at().plus(TTL).isAfter(now));
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, entry);
    }

    /** Empty, not an error, when the cluster cannot be read: the page shows "no runs" and polls again. */
    public List<ObjectSummary> recentJobs() {
        CachedJobs held = jobsCache;
        if (held != null && held.at().plus(TTL).isAfter(clock.instant())) {
            return held.jobs();
        }
        List<ObjectSummary> fresh;
        try {
            fresh = source.jobs().stream()
                    .filter(Scope::ours)
                    .map(WorkloadDescriber::jobSummary)
                    .sorted(Comparator.comparing(ObjectSummary::at,
                            Comparator.nullsLast(Comparator.<String>reverseOrder())))
                    .limit(RECENT_JOBS)
                    .toList();
        } catch (Throwable e) {
            if (Failures.resetsTheClient(e)) {
                source.reset();
            }
            fresh = List.of();
        }
        jobsCache = new CachedJobs(fresh, clock.instant());
        return fresh;
    }

    private ObjectDetail read(ObjectKind kind, String name, Instant now) {
        return switch (kind) {
            case ROUTE -> {
                GenericKubernetesResource route = ours(source.route(name), kind, name);
                Object spec = route.getAdditionalProperties().get("spec");
                String host = spec instanceof Map<?, ?> m && m.get("host") != null
                        ? String.valueOf(m.get("host")) : null;
                yield NetworkDescriber.route(route, host == null ? null : source.probe(host))
                        .withEvents(EventLines.from(source.events("Route", name)));
            }
            case SERVICE -> NetworkDescriber.service(ours(source.service(name), kind, name),
                            source.endpointSlices(name), source.networkPolicies())
                    .withEvents(EventLines.from(source.events("Service", name)));
            case DEPLOYMENT -> {
                Deployment d = ours(source.deployment(name), kind, name);
                yield WorkloadDescriber.deployment(d, source.replicaSets(name),
                                source.hpa(name).orElse(null), source.pods(name), now)
                        .withEvents(EventLines.from(source.events("Deployment", name)));
            }
            case POD -> WorkloadDescriber.pod(ours(source.pod(name), kind, name), now)
                    .withEvents(EventLines.from(source.events("Pod", name)));
            case HPA -> WorkloadDescriber.hpa(ours(source.hpa(name), kind, name), now)
                    .withEvents(EventLines.from(source.events("HorizontalPodAutoscaler", name)));
            case JOB -> {
                Job j = ours(source.job(name), kind, name);
                List<Pod> pods = WorkloadDescriber.isLoad(j)
                        ? source.pods("rembayung-load").stream().filter(p -> ownedBy(p, name)).toList()
                        : List.of();
                yield WorkloadDescriber.job(j, pods, now)
                        .withEvents(EventLines.from(source.events("Job", name)));
            }
            case CRONJOB -> {
                CronJob cron = ours(source.cronJob(name), kind, name);
                List<Job> runs = source.jobs().stream().filter(j -> ownedBy(j, name)).toList();
                yield WorkloadDescriber.cronJob(cron, runs, now)
                        .withEvents(EventLines.from(source.events("CronJob", name)));
            }
        };
    }

    private static <T extends HasMetadata> T ours(Optional<T> found, ObjectKind kind, String name) {
        return found.filter(Scope::ours)
                .orElseThrow(() -> new ObjectNotFound(kind.path() + " " + name + " does not exist here"));
    }

    private static boolean ownedBy(HasMetadata object, String owner) {
        var refs = object.getMetadata().getOwnerReferences();
        return refs != null && refs.stream().anyMatch(r -> owner.equals(r.getName()));
    }
}
