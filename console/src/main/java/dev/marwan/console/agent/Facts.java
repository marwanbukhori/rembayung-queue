package dev.marwan.console.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The facts of one run, numbered F1, F2, … in the order they were learned. */
public final class Facts {

    private final List<Fact> facts = new ArrayList<>();

    public Facts() { }

    Facts(List<Fact> existing) {
        facts.addAll(existing);
    }

    public Fact add(String source, String label, String value) {
        Fact fact = new Fact("F" + (facts.size() + 1), source, label, value);
        facts.add(fact);
        return fact;
    }

    public List<Fact> all() {
        return List.copyOf(facts);
    }

    public Optional<Fact> get(String id) {
        return facts.stream().filter(f -> f.id().equals(id)).findFirst();
    }
}
