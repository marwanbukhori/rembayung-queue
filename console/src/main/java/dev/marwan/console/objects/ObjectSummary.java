package dev.marwan.console.objects;

/**
 * One row in a list of objects, enough to pick one.
 *
 * @param at when it started, ISO-8601, or null
 */
public record ObjectSummary(String kind, String name, String tone, String headline, String at) { }
