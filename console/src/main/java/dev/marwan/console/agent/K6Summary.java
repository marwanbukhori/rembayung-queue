package dev.marwan.console.agent;

import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What k6 says about a run, from the one line drop.js prints at the end.
 *
 * k6's own end-of-run table is for people and changes shape between versions;
 * the script's handleSummary prints {@code K6_SUMMARY {json}} instead, and this
 * reads that. The last such line wins, because a retried container would print
 * twice into the same log.
 */
public record K6Summary(int vus, int iterations, int booked, int rejected, int notClean,
                        double p50, double p95, double max, long durationMs,
                        Integer joined, Integer admitted, Integer soldOutAtJoin, Integer gaveUp,
                        Integer refusedAfterAdmission, Integer soldOut, Integer overloaded, Integer faults,
                        Integer queueWaitP50, Integer queueWaitP95, Integer queueWaitMax,
                        Integer partySize, Integer patienceSeconds,
                        Integer faultsAtJoin, Integer faultsAtBooking) {

    /** True when the run's script counted each customer's path; older scripts did not. */
    public boolean hasOutcomes() {
        return joined != null && admitted != null && gaveUp != null;
    }

    static final String MARKER = "K6_SUMMARY ";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static Optional<K6Summary> parse(String log) {
        if (log == null) {
            return Optional.empty();
        }
        int at = log.lastIndexOf(MARKER);
        if (at < 0) {
            return Optional.empty();
        }
        int end = log.indexOf('\n', at);
        String body = log.substring(at + MARKER.length(), end < 0 ? log.length() : end).trim();
        try {
            JsonNode n = JSON.readTree(body);
            return Optional.of(new K6Summary(n.path("vus").asInt(), n.path("iterations").asInt(),
                    n.path("booked").asInt(), n.path("rejected").asInt(), n.path("notClean").asInt(),
                    n.path("p50").asDouble(), n.path("p95").asDouble(), n.path("max").asDouble(),
                    n.path("durationMs").asLong(),
                    opt(n, "joined"), opt(n, "admitted"), opt(n, "soldOutAtJoin"), opt(n, "gaveUp"),
                    opt(n, "refusedAfterAdmission"), opt(n, "soldOut"), opt(n, "overloaded"), opt(n, "faults"),
                    opt(n, "queueWaitP50"), opt(n, "queueWaitP95"), opt(n, "queueWaitMax"),
                    opt(n, "partySize"), opt(n, "patienceSeconds"),
                    opt(n, "faultsAtJoin"), opt(n, "faultsAtBooking")));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static Integer opt(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asInt() : null;
    }
}
