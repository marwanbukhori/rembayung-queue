package dev.marwan.console.objects;

import java.util.Locale;
import java.util.Optional;

/** What a Logs tab asks for. Key holders choose; everyone else gets EVENTS. */
public enum LogFilter {
    ALL, WARN, EVENTS;

    public String param() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<LogFilter> parse(String value) {
        for (LogFilter f : values()) {
            if (f.param().equals(value)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}
