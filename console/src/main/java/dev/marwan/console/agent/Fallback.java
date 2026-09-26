package dev.marwan.console.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The report a run gets when the model cannot write one: built from the facts
 * alone, by rules, so it is always true and always passes validation.
 *
 * Dull on purpose, but it tells the customer story when the funnel facts are
 * there - "88 of 200 booked; 105 gave up waiting" - because that is the one
 * thing a reader of any report wants answered.
 */
public final class Fallback {

    /** Each way out of the funnel, and how a sentence names it. */
    static final List<Map.Entry<String, String>> OUTCOMES = List.of(
            Map.entry("Gave up waiting (403)", "gave up waiting in the queue and were refused (403)"),
            Map.entry("Sold out at the queue (409)", "found the queue already sold out (409)"),
            Map.entry("Sold out at booking (409)", "were admitted but the seats had sold out (409)"),
            Map.entry("Admitted but refused (403)", "were admitted but refused at booking (403)"),
            Map.entry("Overloaded (503)", "were turned away by an overloaded service (503)"),
            Map.entry("Faults at the queue", "hit a fault joining the queue"),
            Map.entry("Faults at booking", "hit a fault while booking"),
            Map.entry("Other faults", "hit another fault"),
            Map.entry("Did not finish", "did not finish (cut off by the run's time limit or an unreadable reply)"));

    private Fallback() { }

    public static Report from(Facts facts) {
        List<Claim> summary = new ArrayList<>();
        List<Claim> customers = new ArrayList<>();
        List<Claim> capacity = new ArrayList<>();
        List<Claim> errors = new ArrayList<>();
        List<Claim> look = new ArrayList<>();
        List<Fact> pool = new ArrayList<>();

        Optional<Fact> arrived = find(facts, "Arrived");
        Optional<Fact> booked = find(facts, "Booked");
        Optional<Fact> seats = find(facts, "Seats taken by this run");
        Optional<Fact> oversold = find(facts, "Seats oversold").filter(f -> f.value().matches("\\d+"));

        if (arrived.isPresent() && booked.isPresent()) {
            String text = booked.get().value() + " of " + arrived.get().value() + " customers booked"
                    + seats.map(s -> " " + s.value() + " seats").orElse("")
                    + oversold.map(o -> "; oversold " + o.value()).orElse("") + ".";
            summary.add(new Claim(text, ids(booked, arrived, seats, oversold)));
        } else {
            find(facts, "Bookings: clean, rejected, not clean")
                    .ifPresent(f -> summary.add(claim("Bookings: " + f.value() + ".", f)));
            oversold.ifPresent(o -> summary.add(claim(o.value().equals("0") ? "No seat was oversold."
                    : o.value() + " seats were oversold.", o)));
        }

        for (Map.Entry<String, String> out : OUTCOMES) {
            find(facts, out.getKey()).ifPresent(f -> customers.add(claim(f.value() + " " + out.getValue() + ".", f)));
        }
        find(facts, "Admit rate").filter(f -> !f.value().startsWith("unavailable"))
                .ifPresent(f -> customers.add(claim("The queue admitted customers at " + f.value() + ".", f)));

        for (Fact f : facts.all()) {
            String v = f.value();
            if (v.startsWith("unavailable")) {
                errors.add(claim(f.label() + " could not be read (" + v + ").", f));
            } else if (f.label().startsWith("Peak DB pool in use")) {
                pool.add(f);
            } else if (f.label().startsWith("Peak replicas")) {
                capacity.add(claim(f.label() + ": " + v + ".", f));
            } else if (f.label().startsWith("Bookings") && !v.endsWith(" 0 not clean")) {
                errors.add(claim("Bookings: " + v + ".", f));
            } else if ((f.label().startsWith("Most 5xx") || f.label().startsWith("Pool timeouts")) && !v.equals("0")) {
                errors.add(claim(f.label() + ": " + v + ".", f));
            } else if ((f.label().equals("Pod restarts") || f.label().startsWith("Warning events")) && !v.equals("none")) {
                errors.add(claim(f.label() + ": " + v + ".", f));
            }
        }
        if (!pool.isEmpty()) {
            capacity.add(0, new Claim("Peak DB pool in use: " + String.join(", ", pool.stream()
                    .map(f -> f.label().substring(f.label().lastIndexOf(", ") + 2) + " " + f.value()).toList()) + ".",
                    pool.stream().map(Fact::id).toList()));
        }

        Optional<Fact> seatTime = find(facts, "Time to seat every customer at that rate");
        Optional<Fact> rate = find(facts, "Bookings committed per second (peak)");
        if (seatTime.isPresent() && rate.isPresent()) {
            capacity.add(new Claim("At " + rate.get().value() + " bookings committed per second, seating every customer"
                    + " would take " + seatTime.get().value() + ".", List.of(rate.get().id(), seatTime.get().id())));
        }
        Optional<Fact> gaveUp = find(facts, "Gave up waiting (403)");
        Optional<Fact> joined = find(facts, "Joined the queue");
        if (gaveUp.isPresent() && joined.isPresent()
                && number(gaveUp.get()) * 2 > number(joined.get())) {
            look.add(new Claim("Most customers gave up waiting: raise the admit rate or the patience.",
                    List.of(gaveUp.get().id(), joined.get().id())));
        }
        Optional<Fact> size = find(facts, "DB pool size per booking-service pod");
        size.flatMap(s -> pool.stream().filter(p -> p.value().equals(s.value())).findFirst())
                .ifPresent(p -> look.add(new Claim("The pool saturated: look at how long each booking holds a connection.",
                        List.of(p.id(), size.get().id()))));

        if (errors.isEmpty() && !facts.all().isEmpty()) {
            errors.add(new Claim("No errors, pool timeouts, restarts or warnings were recorded.",
                    List.of(facts.all().get(0).id())));
        }
        if (summary.isEmpty() && !facts.all().isEmpty()) {
            summary.add(claim("The run finished and its facts were gathered.", facts.all().get(0)));
        }
        return Report.sections(summary, customers, capacity, errors, look).withBeforeAfter(beforeAfter(facts));
    }

    /**
     * Wave 1 beside wave 2, one line a measure, then a verdict from the two
     * that matter most: did wave 2 meet more ready pods, and did it wait less?
     */
    static List<Claim> beforeAfter(Facts facts) {
        List<Claim> out = new ArrayList<>();
        if (find(facts, "Wave 2 · Arrived").isEmpty()) {
            return out;
        }
        List<Fact> pods1 = facts.all().stream().filter(f -> f.label().startsWith("Wave 1 · Ready pods at start, ")).toList();
        for (Fact one : pods1) {
            String hpa = one.label().substring("Wave 1 · Ready pods at start, ".length());
            find(facts, "Wave 2 · Ready pods at start, " + hpa).ifPresent(two -> out.add(new Claim(
                    "Ready pods at start, " + hpa + ": wave 1 " + one.value() + ", wave 2 " + two.value() + ".",
                    List.of(one.id(), two.id()))));
        }
        for (String measure : List.of("Latency p95 / max", "Overloaded (503)", "Booked")) {
            Optional<Fact> one = find(facts, "Wave 1 · " + measure);
            Optional<Fact> two = find(facts, "Wave 2 · " + measure);
            if (one.isPresent() && two.isPresent()) {
                out.add(new Claim(measure + ": wave 1 " + one.get().value() + ", wave 2 " + two.get().value() + ".",
                        List.of(one.get().id(), two.get().id())));
            }
        }
        Optional<Fact> p1 = find(facts, "Wave 1 · Latency p95 / max");
        Optional<Fact> p2 = find(facts, "Wave 2 · Latency p95 / max");
        Optional<Fact> g1 = find(facts, "Wave 1 · Ready pods at start, queue-gate").or(() -> pods1.stream().findFirst());
        Optional<Fact> g2 = g1.flatMap(g -> find(facts, g.label().replace("Wave 1 · ", "Wave 2 · ")));
        if (g1.isPresent() && g2.isPresent()) {
            long before = leading(g1.get());
            long after = leading(g2.get());
            boolean faster = p1.isPresent() && p2.isPresent() && leading(p2.get()) < leading(p1.get());
            String verdict = after == before ? "Scaling did not happen before wave 2: it met the same ready pods as wave 1."
                    : after > before && faster ? "Scaling helped: wave 2 met more ready pods and a lower p95 than wave 1."
                    : "No clear difference between the waves: more pods did not bring a lower p95.";
            List<String> ids = new ArrayList<>(List.of(g1.get().id(), g2.get().id()));
            p1.ifPresent(f -> ids.add(f.id()));
            p2.ifPresent(f -> ids.add(f.id()));
            out.add(new Claim(verdict, ids));
        }
        return out;
    }

    /** The first whole number in a fact's value: "2100 / 4000 ms" is 2100. */
    private static long leading(Fact f) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(f.value());
        return m.find() ? Long.parseLong(m.group()) : 0;
    }

    private static Optional<Fact> find(Facts facts, String label) {
        return facts.all().stream().filter(f -> f.label().equals(label)).findFirst();
    }

    @SafeVarargs
    private static List<String> ids(Optional<Fact>... facts) {
        return Stream.of(facts).flatMap(Optional::stream).map(Fact::id).toList();
    }

    private static long number(Fact f) {
        try {
            return Long.parseLong(f.value());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Claim claim(String text, Fact fact) {
        return new Claim(text, List.of(fact.id()));
    }
}
