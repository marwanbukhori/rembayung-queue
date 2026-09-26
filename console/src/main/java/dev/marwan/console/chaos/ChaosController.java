package dev.marwan.console.chaos;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.marwan.console.auth.AccessKey;
import dev.marwan.console.auth.KeyFilter;

/** Drills from the page. Starting or ending one needs the key, here as well as in KeyFilter. */
@RestController
public class ChaosController {

    public record Inject(String fault) { }

    private final ChaosService chaos;
    private final AccessKey key;

    public ChaosController(ChaosService chaos, AccessKey key) {
        this.chaos = chaos;
        this.key = key;
    }

    @GetMapping("/api/chaos")
    public Map<String, Object> current() {
        return chaos.current().<Map<String, Object>>map(a -> Map.of("fault", a.fault(),
                "startedAt", a.startedAt().toString(), "until", a.until().toString())).orElse(Map.of());
    }

    @PostMapping("/api/chaos")
    public ResponseEntity<?> inject(@RequestBody Inject body, HttpServletRequest request) {
        if (!keyed(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "KEY_REQUIRED"));
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(chaos.inject(body.fault()));
        } catch (ChaosService.Busy e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.active() == null
                    ? Map.of("error", "BUSY") : Map.of("error", "BUSY", "active", e.active()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "UNKNOWN_FAULT", "detail", e.getMessage()));
        }
    }

    @DeleteMapping("/api/chaos")
    public ResponseEntity<?> end(HttpServletRequest request) {
        if (!keyed(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "KEY_REQUIRED"));
        }
        chaos.end();
        return ResponseEntity.noContent().build();
    }

    private boolean keyed(HttpServletRequest request) {
        String presented = request.getHeader(KeyFilter.HEADER);
        return key.accepts(presented != null ? presented : request.getParameter(KeyFilter.QUERY_PARAM));
    }
}
