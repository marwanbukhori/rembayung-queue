package dev.marwan.console.objects;

import java.util.List;

/**
 * Everything the inspector shows about one object, in one shape for every kind.
 *
 * Generic on purpose: label and value pairs rather than a record per kind, so
 * the page renders a Route and a CronJob with the same code and a new kind is
 * a describer method, not a new component.
 *
 * @param available false when the cluster could not be read; {@code detail} says why
 * @param tone      one of {@link #OK}, {@link #WARN}, {@link #BAD}, {@link #NEUTRAL}
 * @param headline  one line: the state a reader should take away
 */
public record ObjectDetail(String kind, String name, boolean available, String detail,
                           String tone, String headline,
                           List<Fact> facts, List<Link> related, List<EventLine> events) {

    public static final String OK = "ok";
    public static final String WARN = "warn";
    public static final String BAD = "bad";
    public static final String NEUTRAL = "neutral";

    /** @param tone null when the fact is plain information */
    public record Fact(String label, String value, String tone) {
        public Fact(String label, String value) {
            this(label, value, null);
        }
    }

    /** Another object this one points at, for the inspector to link to. */
    public record Link(String kind, String name, String label, String tone) { }

    /** @param at ISO-8601 instant, or null when the event carried no time at all */
    public record EventLine(String at, String type, String reason, String message, int count) { }

    public static ObjectDetail unavailable(ObjectKind kind, String name, String reason) {
        return new ObjectDetail(kind.path(), name, false, reason, NEUTRAL,
                "not readable right now", List.of(), List.of(), List.of());
    }

    public ObjectDetail withMoreFacts(List<Fact> more) {
        List<Fact> all = new java.util.ArrayList<>(facts);
        all.addAll(more);
        return new ObjectDetail(kind, name, available, detail, tone, headline, List.copyOf(all), related, events);
    }

    public ObjectDetail withEvents(List<EventLine> lines) {
        return new ObjectDetail(kind, name, available, detail, tone, headline, facts, related, lines);
    }
}
