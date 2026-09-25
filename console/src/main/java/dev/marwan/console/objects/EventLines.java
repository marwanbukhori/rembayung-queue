package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Event;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Kubernetes Events for one object, newest first, as the inspector lists them.
 *
 * Two timestamp fields exist and events use either: the older lastTimestamp,
 * or eventTime alone on events from newer controllers. Sorting by only one of
 * them puts the other kind at the bottom, which is where the probe failures
 * of a rollout would have ended up.
 */
final class EventLines {

    static final int LIMIT = 20;

    private EventLines() {
    }

    static List<ObjectDetail.EventLine> from(List<Event> events) {
        return events.stream()
                .map(e -> new ObjectDetail.EventLine(when(e), e.getType(), e.getReason(), e.getMessage(),
                        e.getCount() == null ? 1 : e.getCount()))
                .sorted(Comparator.comparing(ObjectDetail.EventLine::at,
                        Comparator.nullsLast(Comparator.<String>reverseOrder())))
                .limit(LIMIT)
                .toList();
    }

    /** Normalised to second-precision ISO so lexical order is time order. */
    private static String when(Event e) {
        String raw = e.getLastTimestamp() != null ? e.getLastTimestamp()
                : e.getEventTime() != null ? e.getEventTime().getTime()
                : e.getFirstTimestamp();
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw).truncatedTo(ChronoUnit.SECONDS).toString();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
