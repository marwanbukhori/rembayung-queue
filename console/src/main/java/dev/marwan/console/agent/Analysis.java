package dev.marwan.console.agent;

import java.time.Instant;
import java.util.List;

/**
 * Everything the agent knew and did for one run, as stored.
 *
 * {@code source} is "model" when the report is the model's and passed
 * validation, "fallback" when it was built from the facts alone; {@code note}
 * says why a fallback happened, and {@code problems} what validation found.
 */
public record Analysis(String job, String dropId, Instant start, Instant end,
                       List<Fact> facts, List<TrailStep> trail, Report report,
                       String model, String source, String note, List<String> problems,
                       Instant analysedAt, long millis) {

    /**
     * Which run this is. The Job name alone is not enough: every rush on a drop
     * reuses "load-<drop>", so the start time tells two runs of one drop apart.
     */
    public String key() {
        return RunWindow.key(job, start);
    }
}
