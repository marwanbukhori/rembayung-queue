package dev.marwan.console.metrics;

import java.util.List;

/**
 * One line on a chart.
 *
 * @param points [epochSeconds, value] pairs, oldest first, with gaps where Prometheus had none
 */
public record Series(String label, List<double[]> points) { }
