package dev.marwan.console.objects;

import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pod's log lines for the inspector's Logs tab.
 *
 * One upstream read per pod per two seconds, shared by every viewer: the last
 * 500 lines are fetched and cached, and each request filters that snapshot by
 * its own cursor and filter. Everything past the cache is per request and cheap.
 */
@Component
public class PodLogs {

    static final Duration TTL = Duration.ofSeconds(2);
    static final int TAIL = LogLines.MAX_LINES;
    static final int FIRST_PAGE = 200;
    static final int MAX_PODS = 64;
    static final String REDIS_NOTE =
            "redis logs only its own lifecycle - starting, ready, warnings - never the commands it serves.";
    static final String RESTRICTED_NOTE =
            "Showing app events only. Raw logs need the console key.";
    static final String NOT_STARTED = "No logs yet: the container has not started.";

    private final ObjectSource source;
    private final Clock clock;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    private record Snapshot(List<LogLine> lines, boolean redis, String note, Instant at) { }

    public PodLogs(ObjectSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    public LogPage read(String pod, String since, String filterParam, boolean keyHolder) {
        Snapshot snap;
        try {
            snap = snapshot(pod);
        } catch (ObjectNotFound e) {
            throw e;
        } catch (Throwable e) {
            if (Failures.resetsTheClient(e)) {
                source.reset();
            }
            return new LogPage(pod, false, KubernetesAccess.summarise(e), LogFilter.EVENTS.param(),
                    !keyHolder, null, List.of(), since);
        }

        boolean restricted = !keyHolder && !snap.redis();
        LogFilter filter = restricted ? LogFilter.EVENTS
                : LogFilter.parse(filterParam).orElse(LogFilter.ALL);

        List<LogLine> shown = LogLines.filter(after(snap.lines(), since), filter);
        if (parse(since) == null) {
            shown = shown.subList(Math.max(0, shown.size() - FIRST_PAGE), shown.size());
        }
        String latest = snap.lines().isEmpty() ? since : snap.lines().getLast().at();
        String note = snap.note() != null ? snap.note()
                : snap.redis() ? REDIS_NOTE
                : restricted ? RESTRICTED_NOTE : null;
        return new LogPage(pod, true, null, filter.param(), restricted, note, shown, latest);
    }

    private Snapshot snapshot(String pod) {
        Snapshot held = cache.get(pod);
        if (held != null && held.at().plus(TTL).isAfter(clock.instant())) {
            return held;
        }
        Pod found = source.pod(pod).filter(Scope::ours)
                .orElseThrow(() -> new ObjectNotFound("pod " + pod + " does not exist here"));
        Map<String, String> labels = found.getMetadata().getLabels();
        boolean redis = labels != null && "redis".equals(labels.get("app"));
        Snapshot fresh;
        try {
            fresh = new Snapshot(LogLines.parse(source.podLog(pod, TAIL)), redis, null, clock.instant());
        } catch (KubernetesClientException e) {
            if (e.getCode() == 400 && String.valueOf(e.getMessage()).contains("waiting to start")) {
                fresh = new Snapshot(List.of(), redis, NOT_STARTED, clock.instant());
            } else {
                throw e;
            }
        }
        if (cache.size() >= MAX_PODS) {
            Instant now = clock.instant();
            cache.values().removeIf(s -> !s.at().plus(TTL).isAfter(now));
            if (cache.size() >= MAX_PODS) {
                cache.clear();
            }
        }
        cache.put(pod, fresh);
        return fresh;
    }

    /** Lines strictly after the cursor; an unreadable cursor means none was sent. */
    private static List<LogLine> after(List<LogLine> lines, String since) {
        Instant cursor = parse(since);
        if (cursor == null) {
            return lines;
        }
        return lines.stream().filter(l -> {
            Instant at = parse(l.at());
            return at != null && at.isAfter(cursor);
        }).toList();
    }

    private static Instant parse(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
