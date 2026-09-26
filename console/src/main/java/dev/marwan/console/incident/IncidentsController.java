package dev.marwan.console.incident;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
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
import dev.marwan.console.slo.SloService;

/**
 * Incidents and the SLOs for the page. Reading is public. Approving or
 * dismissing a proposal needs the key and exists only here - not in MCP - so
 * the only way the agent's advice changes the cluster is a person's click.
 */
@RestController
public class IncidentsController {

    public record Summary(String id, String kind, String fault, String status, Instant openedAt, Instant resolvedAt,
                          int proposals, boolean pendingProposal) { }

    private final IncidentStore store;
    private final Remediation remediation;
    private final SloService slo;
    private final AccessKey key;

    public IncidentsController(IncidentStore store, Remediation remediation, SloService slo, AccessKey key) {
        this.store = store;
        this.remediation = remediation;
        this.slo = slo;
        this.key = key;
    }

    @GetMapping("/api/incidents")
    public List<Summary> list() {
        return store.list().stream().map(i -> new Summary(i.id, i.kind, i.fault, i.status, i.openedAt, i.resolvedAt,
                i.proposals.size(), i.proposals.stream().anyMatch(p -> "pending".equals(p.status())))).toList();
    }

    @GetMapping("/api/incidents/{id}")
    public ResponseEntity<?> one(@PathVariable String id) {
        return store.get(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND")));
    }

    @GetMapping("/api/slo")
    public Map<String, Object> slo() {
        return Map.of("now", slo.now(), "history", slo.history(Duration.ofMinutes(15)),
                "targets", Map.of("success", 0.99, "p95Seconds", 2.0));
    }

    @PostMapping("/api/incidents/{id}/proposals/{n}/approve")
    public ResponseEntity<?> approve(@PathVariable String id, @PathVariable int n, HttpServletRequest request) {
        return keyed(request) ? answer(remediation.approve(id, n)) : refused();
    }

    @PostMapping("/api/incidents/{id}/proposals/{n}/dismiss")
    public ResponseEntity<?> dismiss(@PathVariable String id, @PathVariable int n, HttpServletRequest request) {
        return keyed(request) ? answer(remediation.dismiss(id, n)) : refused();
    }

    private static ResponseEntity<?> answer(Remediation.Result result) {
        return switch (result) {
            case APPLIED, DISMISSED -> ResponseEntity.ok(Map.of("result", result.name()));
            case FAILED -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("result", "FAILED",
                    "detail", "the cluster refused the change; the incident's timeline says why"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND"));
            case NOT_PENDING -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "NOT_PENDING",
                    "detail", "the proposal was already decided, or the incident has closed"));
        };
    }

    private static ResponseEntity<?> refused() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "KEY_REQUIRED"));
    }

    private boolean keyed(HttpServletRequest request) {
        String presented = request.getHeader(KeyFilter.HEADER);
        return key.accepts(presented != null ? presented : request.getParameter(KeyFilter.QUERY_PARAM));
    }
}
