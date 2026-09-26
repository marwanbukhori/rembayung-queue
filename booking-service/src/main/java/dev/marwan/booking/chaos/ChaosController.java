package dev.marwan.booking.chaos;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's lever for a drill. Cluster-internal only, like every /internal
 * path here: booking-service has no Route, and its NetworkPolicy admits the
 * gate and the console and nothing else.
 */
@RestController
@RequestMapping("/internal/chaos")
public class ChaosController {

    public record Start(String fault, Integer seconds) { }

    private final ChaosState chaos;

    public ChaosController(ChaosState chaos) {
        this.chaos = chaos;
    }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody Start request) {
        try {
            return ResponseEntity.ok(chaos.start(request.fault(),
                    request.seconds() == null ? ChaosState.MAX_SECONDS : request.seconds()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "UNKNOWN_FAULT"));
        }
    }

    @GetMapping
    public Map<String, Object> current() {
        Map<String, Object> body = new HashMap<>();
        chaos.current().ifPresent(a -> {
            body.put("fault", a.fault());
            body.put("until", a.until().toString());
        });
        return body;
    }

    @DeleteMapping
    public ResponseEntity<Void> end() {
        chaos.clear();
        return ResponseEntity.noContent().build();
    }
}
