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
    public record Summary(String job, String dropId, Instant start, Instant end, Instant analysedAt,
                          String source, String model, String note, int claims) { }

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
        return store.list().stream().map(a -> new Summary(a.job(), a.dropId(), a.start(), a.end(), a.analysedAt(),
                a.source(), a.model(), a.note(), a.report().all().size())).toList();
    }

    @GetMapping("/api/analyses/{job}")
    public ResponseEntity<?> one(@PathVariable String job) {
        return store.get(job).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND")));
    }

    @PostMapping("/api/analyses/{job}/rerun")
    public ResponseEntity<Map<String, String>> rerun(@PathVariable String job, HttpServletRequest request) {
        String presented = request.getHeader(KeyFilter.HEADER);
        if (presented == null) {
            presented = request.getParameter(KeyFilter.QUERY_PARAM);
        }
        if (!key.accepts(presented)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "KEY_REQUIRED"));
        }
        return switch (runAnalyst.rerun(job)) {
            case STARTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "started"));
            case BUSY -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "BUSY"));
            case UNKNOWN -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND"));
        };
    }
}
