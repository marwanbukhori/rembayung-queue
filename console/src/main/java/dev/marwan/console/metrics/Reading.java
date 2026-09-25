package dev.marwan.console.metrics;

/** One value right now, read straight from a pod or the Kubernetes API. */
public record Reading(String label, double value) { }
