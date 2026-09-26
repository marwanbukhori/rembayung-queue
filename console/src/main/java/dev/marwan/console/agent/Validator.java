package dev.marwan.console.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rule that makes the report worth reading: every claim cites facts that
 * exist, and every number it states appears in one of those facts.
 *
 * An 8B model writes fluent sentences whether or not it has read the facts
 * correctly. It cannot, through this, put a number in front of a reader that
 * the run did not produce. Numbers are matched as whole tokens, so "5" in
 * "5xx" or "55" inside a pod name neither counts as stated nor as supported.
 */
public class Validator {

    /**
     * A number standing on its own: not inside a word ("55bdc8", "p95", "5xx"),
     * but still a number when a unit is stuck to it ("2400ms", "42s", "3.5x",
     * "75%") or a sentence ends on it ("peaked at 7.").
     */
    static final Pattern NUMBER = Pattern.compile(
            "(?<![\\w.])\\d+(?:\\.\\d+)?(?=(?:ms|s|m|h|x|k)?(?![\\w])(?!\\.\\d))");
    static final Pattern FACT_ID = Pattern.compile("\\bF\\d+\\b");

    public List<String> problems(Report report, Facts facts) {
        List<String> problems = new ArrayList<>();
        if (report == null || report.all().isEmpty()) {
            problems.add("the report has no claims");
            return problems;
        }
        // A two-wave run exists to compare its waves: that comparison is the report's point.
        if (facts.all().stream().anyMatch(f -> f.label().equals("Wave 2 · Arrived"))) {
            if (report.beforeAfter().isEmpty()) {
                problems.add("the before_after section is empty: compare wave 1 with wave 2");
            }
            for (Claim c : report.beforeAfter()) {
                boolean one = cites(c, facts, "Wave 1 · ");
                boolean two = cites(c, facts, "Wave 2 · ");
                if (!one || !two) {
                    problems.add("\"" + c.text() + "\" must cite facts from both waves");
                }
            }
        }
        // With the funnel known, a report that does not say where the customers went has missed the point.
        if (facts.all().stream().anyMatch(f -> f.label().equals("Arrived"))) {
            if (report.summary().isEmpty() && report.wentWell().isEmpty()) {
                problems.add("the summary section is empty");
            }
            if (report.customers().isEmpty() && report.wentWell().isEmpty()) {
                problems.add("the customers section is empty: say where the customers went");
            }
        }
        Set<String> vocabulary = new HashSet<>();
        facts.all().forEach(f -> vocabulary.addAll(numbers(f.label())));
        for (Claim claim : report.all()) {
            String text = claim.text() == null ? "" : claim.text();
            if (claim.facts() == null || claim.facts().isEmpty()) {
                problems.add("\"" + text + "\" cites no facts");
                continue;
            }
            // Numbers in any fact's label - status codes like 503, "p95" - are vocabulary, usable anywhere.
            // Numbers in values are what a claim asserts, and those must come from the facts it cites.
            Set<String> supported = new HashSet<>(vocabulary);
            for (String id : claim.facts()) {
                facts.get(id).ifPresentOrElse(
                        f -> {
                            supported.addAll(numbers(f.value()));
                            supported.addAll(numbers(f.label()));
                        },
                        () -> problems.add("\"" + text + "\" cites " + id + ", which does not exist"));
            }
            for (String n : numbers(FACT_ID.matcher(text).replaceAll(" "))) {
                if (!supported.contains(n)) {
                    problems.add("\"" + text + "\" states " + n + ", which is in none of " + claim.facts());
                }
            }
        }
        return problems;
    }

    private static boolean cites(Claim c, Facts facts, String prefix) {
        return c.facts() != null && c.facts().stream()
                .anyMatch(id -> facts.get(id).map(f -> f.label().startsWith(prefix)).orElse(false));
    }

    static Set<String> numbers(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(text.replaceAll("(?<=\\d),(?=\\d{3})", ""));
        while (m.find()) {
            out.add(normalise(m.group()));
        }
        return out;
    }

    private static String normalise(String n) {
        return n.contains(".") ? n.replaceAll("0+$", "").replaceAll("\\.$", "") : n.replaceFirst("^0+(?=\\d)", "");
    }
}
