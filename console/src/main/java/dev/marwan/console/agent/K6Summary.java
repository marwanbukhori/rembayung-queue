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
                        double p50, double p95, double max, long durationMs) {

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
                    n.path("durationMs").asLong()));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}
