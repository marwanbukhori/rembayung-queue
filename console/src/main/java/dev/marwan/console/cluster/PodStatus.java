package dev.marwan.console.cluster;

/**
 * One pod, in the terms the page shows: name, readiness, what it asked the
 * scheduler for, how often it has died, and how long it has been up.
 */
public record PodStatus(
        String name,
        String ready,
        boolean healthy,
        String cpu,
        int restarts,
        String age) { }
