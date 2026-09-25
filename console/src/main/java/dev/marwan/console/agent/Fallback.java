package dev.marwan.console.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * The report a run gets when the model cannot write one: built from the facts
 * alone, by rules, so it is always true and always passes validation.
 *
 * Dull on purpose. Its job is to make sure no run goes unreported when the
 * shared model is down, slow, or keeps inventing numbers - not to compete
 * with the model at noticing things.
 */
public final class Fallback {

    private Fallback() { }

    public static Report from(Facts facts) {
        List<Claim> well = new ArrayList<>();
        List<Claim> caught = new ArrayList<>();
        List<Claim> look = new ArrayList<>();
        List<Fact> pool = new ArrayList<>();

        for (Fact f : facts.all()) {
            String v = f.value();
            if (v.startsWith("unavailable")) {
                caught.add(claim(f.label() + " could not be read (" + v + ").", f));
            } else if (f.label().equals("Seats oversold")) {
                (v.equals("0") ? well : caught).add(claim(v.equals("0") ? "No seat was oversold."
                        : v + " seats were oversold.", f));
            } else if (f.label().startsWith("Bookings")) {
                well.add(claim("Bookings: " + v + ".", f));
            } else if (f.label().startsWith("Peak DB pool in use")) {
                pool.add(f);
            } else if ((f.label().startsWith("Most 5xx") || f.label().startsWith("Pool timeouts")) && !v.equals("0")) {
                caught.add(claim(f.label() + ": " + v + ".", f));
            } else if ((f.label().equals("Pod restarts") || f.label().startsWith("Warning events")) && !v.equals("none")) {
                caught.add(claim(f.label() + ": " + v + ".", f));
            }
        }
        if (!pool.isEmpty()) {
            look.add(new Claim("Peak DB pool in use: " + String.join(", ", pool.stream()
                    .map(f -> f.label().substring(f.label().lastIndexOf(", ") + 2) + " " + f.value()).toList()) + ".",
                    pool.stream().map(Fact::id).toList()));
        }
        if (caught.isEmpty() && !facts.all().isEmpty()) {
            caught.add(new Claim("No errors, pool timeouts, restarts or warnings were recorded.",
                    List.of(facts.all().get(0).id())));
        }
        if (well.isEmpty() && !facts.all().isEmpty()) {
            well.add(claim("The run finished and its facts were gathered.", facts.all().get(0)));
        }
        return new Report(well, caught, look);
    }

    private static Claim claim(String text, Fact fact) {
        return new Claim(text, List.of(fact.id()));
    }
}
