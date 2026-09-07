package dev.marwan.console.observability;

import java.time.Instant;
import java.util.List;

/**
 * What the two observability vendors are actually doing for this system, read
 * from the cluster and from Splunk itself rather than asserted.
 *
 * <h2>Why this exists</h2>
 * Both integrations are invisible from inside the product: Splunk is a token
 * this cluster writes to and cannot read back, and Dynatrace is an agent
 * injected into a JVM by an admission webhook. When either looks empty there is
 * no way, from the console, to tell "wired correctly and quiet" from "silently
 * broken" — and that ambiguity cost a whole afternoon once already, when an
 * empty Splunk search turned out to mean the applications had no log statements
 * on the request path rather than that shipping had failed.
 *
 * So this reports the two things that are checkable without a read credential:
 * whether the collector answers, and which workloads are configured to feed
 * each vendor. It deliberately does not claim to show logs or traces; those
 * live in the vendors' own UIs, which the panel links to.
 *
 * @param splunk    the log pipeline: does the collector answer, and who ships to it
 * @param dynatrace the trace pipeline: which JVMs carry the agent
 * @param checkedAt when these readings were taken, so a stale card says so
 */
public record ObservabilityStatus(Splunk splunk, Dynatrace dynatrace, Instant checkedAt) {

    /**
     * @param endpoint  the collector host, port included, with no token in it
     * @param reachable whether the collector answered its health endpoint
     * @param detail    the collector's own words, or why the probe could not run
     * @param latencyMs how long the collector took to answer, or -1 when it did not.
     *                  Shown beside the reply because "healthy" and "healthy but
     *                  four seconds away" are different states and only one of
     *                  them is worth interrupting a demo for.
     * @param shippers  one entry per workload, saying whether it is configured to ship
     */
    public record Splunk(String endpoint, boolean reachable, String detail,
                         long latencyMs, List<Feed> shippers) { }

    /**
     * @param tenant       the Dynatrace environment these traces land in
     * @param mode         which OneAgent flavour, because it decides what exists to look at
     * @param instrumented one entry per workload, saying whether the agent reached its JVM
     * @param detail       why the reading is what it is
     */
    public record Dynatrace(String tenant, String mode, List<Feed> instrumented, String detail) { }

    /**
     * One workload's relationship with one vendor.
     *
     * @param service the deployment name
     * @param on      whether the wiring is present
     * @param detail  what was found, so a false reading says which piece is missing
     */
    public record Feed(String service, boolean on, String detail) { }
}
