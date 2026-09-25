package dev.marwan.console.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Prometheus text exposition an /actuator/prometheus endpoint serves. */
final class PromText {

    record Sample(Map<String, String> labels, double value) { }

    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private PromText() {
    }

    static List<Sample> samples(String body, String metric) {
        List<Sample> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String name;
            String labels = "";
            String rest;
            int brace = line.indexOf('{');
            int space = line.indexOf(' ');
            if (brace >= 0 && (space < 0 || brace < space)) {
                name = line.substring(0, brace);
                int close = line.lastIndexOf('}');
                labels = line.substring(brace + 1, close);
                rest = line.substring(close + 1).trim();
            } else {
                name = space < 0 ? line : line.substring(0, space);
                rest = space < 0 ? "" : line.substring(space + 1).trim();
            }
            if (!name.equals(metric)) {
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(rest.split("\\s+")[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            Map<String, String> map = new LinkedHashMap<>();
            Matcher m = LABEL.matcher(labels);
            while (m.find()) {
                map.put(m.group(1), m.group(2));
            }
            out.add(new Sample(map, value));
        }
        return out;
    }
}
