package dev.marwan.console.agent;

import java.util.List;

/** One sentence of a report and the ids of the facts it rests on. */
public record Claim(String text, List<String> facts) { }
