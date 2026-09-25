package dev.marwan.console.agent;

import java.util.List;

/** What went well, what the run caught, and what to look at next. */
public record Report(List<Claim> wentWell, List<Claim> caught, List<Claim> lookAt) {

    public List<Claim> all() {
        return java.util.stream.Stream.of(wentWell, caught, lookAt).flatMap(List::stream).toList();
    }
}
