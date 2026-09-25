package dev.marwan.console.agent;

/**
 * One thing known about a run, with an id the report can cite.
 *
 * The value is text rather than a number because facts are read by a model and
 * by people, and "5 of 5" or "unavailable: HTTP 503" say more than a double.
 * The validator reads the numbers back out of it.
 */
public record Fact(String id, String source, String label, String value) { }
