package dev.marwan.console.objects;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raw pod log text into the lines the Logs tab shows.
 *
 * Every service here logs JSON through logstash-logback-encoder, and the
 * business moments carry an `event` field (queue.arrival, booking.claimed,
 * ...). Those are what the public sees. Plumbing keys are dropped from the
 * rendered fields; the rest are the event's own facts.
 *
 * Phones are not logged by design - the services leave them out on purpose -
 * so masking is the net, not the plan. It runs for every reader.
 */
public final class LogLines {

    static final int MAX_LINES = 500;
    static final int MAX_MESSAGE = 2000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PLUMBING = Set.of("@timestamp", "@version", "message", "logger_name",
            "thread_name", "level", "level_value", "service", "tags", "event", "stack_trace", "source");
    private static final Pattern PHONE = Pattern.compile("\\+60\\d{3,11}|(?<![\\d.])60\\d{8,10}(?!\\d)");

    private LogLines() {
    }

    public static List<LogLine> parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        String[] split = raw.split("\n");
        List<LogLine> lines = new ArrayList<>();
        for (int i = Math.max(0, split.length - MAX_LINES); i < split.length; i++) {
            String row = split[i];
            if (row.isBlank()) {
                continue;
            }
            int space = row.indexOf(' ');
            String at = space > 0 ? row.substring(0, space) : null;
            String body = space > 0 ? row.substring(space + 1) : row;
            lines.add(line(at, body));
        }
        return lines;
    }

    static List<LogLine> filter(List<LogLine> lines, LogFilter filter) {
        return switch (filter) {
            case ALL -> lines;
            case WARN -> lines.stream().filter(l -> "WARN".equals(l.level()) || "ERROR".equals(l.level())).toList();
            case EVENTS -> lines.stream().filter(l -> l.event() != null).toList();
        };
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PHONE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String hit = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(hit.substring(0, Math.min(5, hit.length())) + "••••"));
        }
        m.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static LogLine line(String at, String body) {
        if (body.startsWith("{")) {
            try {
                Map<String, Object> json = JSON.readValue(body, Map.class);
                Map<String, String> fields = new LinkedHashMap<>();
                json.forEach((k, v) -> {
                    if (!PLUMBING.contains(k) && v != null) {
                        fields.put(k, mask(cut(String.valueOf(v), 200)));
                    }
                });
                return new LogLine(at, str(json.get("level")), mask(cut(str(json.get("message")), MAX_MESSAGE)),
                        str(json.get("event")), fields);
            } catch (JacksonException e) {
                // Not JSON after all: shown as it came.
            }
        }
        return new LogLine(at, null, mask(cut(body, MAX_MESSAGE)), null, Map.of());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
