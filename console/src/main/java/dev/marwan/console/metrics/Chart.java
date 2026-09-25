package dev.marwan.console.metrics;

import java.util.List;

/**
 * One chart: history from Prometheus, the live value from the pods.
 *
 * @param history null when the history was read; otherwise why it was not
 * @param live    null when the live reading was taken; otherwise why it was not
 */
public record Chart(String name, String unit, String history, List<Series> series,
                    String live, List<Reading> now) { }
