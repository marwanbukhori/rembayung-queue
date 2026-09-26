package dev.marwan.console.agent;

import java.util.List;
import java.util.stream.Stream;

/**
 * A run's report in five sections: the headline, where the customers went,
 * capacity and scaling, errors, and what to look at next.
 *
 * {@code wentWell} and {@code caught} are the first design's lists. Reports
 * stored before the sections still carry them, and are read and shown as they
 * were written; new reports leave them empty.
 */
public record Report(List<Claim> summary, List<Claim> customers, List<Claim> capacity, List<Claim> errors,
                     List<Claim> lookAt, List<Claim> wentWell, List<Claim> caught) {

    public Report {
        summary = orEmpty(summary);
        customers = orEmpty(customers);
        capacity = orEmpty(capacity);
        errors = orEmpty(errors);
        lookAt = orEmpty(lookAt);
        wentWell = orEmpty(wentWell);
        caught = orEmpty(caught);
    }

    public static Report sections(List<Claim> summary, List<Claim> customers, List<Claim> capacity,
                                  List<Claim> errors, List<Claim> lookAt) {
        return new Report(summary, customers, capacity, errors, lookAt, List.of(), List.of());
    }

    public List<Claim> all() {
        return Stream.of(summary, customers, capacity, errors, lookAt, wentWell, caught)
                .flatMap(List::stream).toList();
    }

    private static List<Claim> orEmpty(List<Claim> claims) {
        return claims == null ? List.of() : claims;
    }
}
