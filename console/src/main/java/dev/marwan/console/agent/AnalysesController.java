package dev.marwan.console.agent;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.marwan.console.auth.AccessKey;
import dev.marwan.console.auth.KeyFilter;

/**
 * The agent's reports. Reading is public, like every other read here; asking
 * for a re-analysis spends the shared model's time, so it needs the key - here
 * as well as in KeyFilter, so the rule holds even if the filter's paths change.
 */
@RestController
public class AnalysesController {

    /** One line per run for lists: enough to choose one without loading them all. */
    public record Summary(String key, String job, String dropId, Instant start, Instant end, Instant analysedAt,
                          String source, String model, String note, int claims,
                          Integer customers, Integer booked, Integer seats, Integer oversold, int waves) { }

    private final AnalysisStore store;
    private final RunAnalyst runAnalyst;
    private final AccessKey key;

    public AnalysesController(AnalysisStore store, RunAnalyst runAnalyst, AccessKey key) {
        this.store = store;
        this.runAnalyst = runAnalyst;
        this.key = key;
    }

    @GetMapping("/api/analyses")
    public List<Summary> list() {
        return store.list().stream().map(a -> new Summary(a.key(), a.job(), a.dropId(), a.start(), a.end(), a.analysedAt(),
                a.source(), a.model(), a.note(), a.report().all().size(),
                number(a.facts(), "Arrived"), number(a.facts(), "Booked"),
                number(a.facts(), "Seats taken by this run"), number(a.facts(), "Seats oversold"),
                a.facts().stream().anyMatch(f -> f.label().startsWith("Wave 2 · ")) ? 2 : 1)).toList();
    }

    /**
     * One run's report. The pod_logs facts hold raw log lines, which on this
     * console are for key holders only; without the key they are replaced by
     * a line saying so, and the rest of the report is unchanged.
     */
    @GetMapping("/api/analyses/{job}")
    public ResponseEntity<?> one(@PathVariable String job, HttpServletRequest request) {
        boolean keyed = key.accepts(presented(request));
        return store.get(job).map(a -> keyed ? a : withoutRawLogs(a)).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND")));
    }

    @PostMapping("/api/analyses/{job}/rerun")
    public ResponseEntity<Map<String, String>> rerun(@PathVariable String job, HttpServletRequest request) {
        if (!key.accepts(presented(request))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "KEY_REQUIRED"));
        }
        return switch (runAnalyst.rerun(job)) {
            case STARTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "started"));
            case BUSY -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "BUSY"));
            case UNKNOWN -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND"));
        };
    }

    /** A fact's value as a whole number, or null when it is missing or says something else. */
    static Integer number(List<Fact> facts, String label) {
        return facts.stream().filter(f -> f.label().equals(label) && f.value().matches("\\d{1,9}")).findFirst()
                .map(f -> Integer.valueOf(f.value())).orElse(null);
    }

    private static String presented(HttpServletRequest request) {
        String presented = request.getHeader(KeyFilter.HEADER);
        return presented != null ? presented : request.getParameter(KeyFilter.QUERY_PARAM);
    }

    static Analysis withoutRawLogs(Analysis a) {
        List<Fact> facts = a.facts().stream().map(f -> f.source().equals("tool: pod_logs")
                ? new Fact(f.id(), f.source(), f.label(), "raw log lines: shown with the console key ("
                        + f.value().lines().count() + " lines)")
                : f).toList();
        return new Analysis(a.job(), a.dropId(), a.start(), a.end(), facts, a.trail(), a.report(), a.model(),
                a.source(), a.note(), a.problems(), a.analysedAt(), a.millis());
    }
}
