package dev.marwan.console.cluster;

/**
 * One pod, in the terms the page shows: name, readiness, what it asked the
 * scheduler for, how often it has died, how long it has been up, and its phase.
 *
 * @param phase Running, Succeeded, Pending or Failed, straight from the pod.
 *              Readiness alone cannot tell a finished Job from a crash loop -
 *              both report 0/1 - so a page with only `healthy` painted every
 *              Completed load run as an unhealthy pod, which is the opposite of
 *              what it is.
 */
public record PodStatus(
        String name,
        String ready,
        boolean healthy,
        String cpu,
        int restarts,
        String age,
        String phase) { }
