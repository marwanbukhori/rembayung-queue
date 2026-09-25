package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.IntOrString;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** The small formatting every describer needs, in one place so they agree. */
final class Facts {

    static final String NONE = "—";

    private Facts() {
    }

    static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** "12m", "3h", "2d" from an ISO timestamp, or {@link #NONE}. */
    static String age(String iso, Instant now) {
        if (iso == null) {
            return NONE;
        }
        try {
            Duration d = Duration.between(Instant.parse(iso), now);
            if (d.isNegative()) {
                return "0s";
            }
            if (d.toMinutes() < 1) {
                return d.toSeconds() + "s";
            }
            if (d.toHours() < 1) {
                return d.toMinutes() + "m";
            }
            if (d.toDays() < 1) {
                return d.toHours() + "h";
            }
            return d.toDays() + "d";
        } catch (DateTimeParseException e) {
            return NONE;
        }
    }

    /** The tag of an image, first 12 characters, which is how commits are shown everywhere else. */
    static String tag(String image) {
        if (image == null || !image.contains(":")) {
            return image == null ? NONE : "latest";
        }
        String tag = image.substring(image.lastIndexOf(':') + 1);
        return tag.length() > 12 ? tag.substring(0, 12) : tag;
    }

    static String intOrString(IntOrString value) {
        if (value == null) {
            return NONE;
        }
        return value.getIntVal() != null ? String.valueOf(value.getIntVal()) : value.getStrVal();
    }
}
