package dev.marwan.console.web;

import dev.marwan.console.auth.AccessKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The console key, handed to anyone while CONSOLE_SHARE_KEY is on, so the demo
 * works for whoever opens it: a button on the page fetches it and reloads the
 * page keyed. What limits a stranger is the guards on a run - one per drop,
 * the CPU quota, sandboxes that expire - not the key. Off, this is a fixed 404
 * that never carries the key.
 */
@RestController
public class DemoKeyController {

    private final AccessKey key;
    private final boolean share;

    public DemoKeyController(AccessKey key, @Value("${CONSOLE_SHARE_KEY:false}") boolean share) {
        this.key = key;
        this.share = share;
    }

    @GetMapping("/api/demo-key")
    public ResponseEntity<Map<String, String>> demoKey() {
        if (!share || !key.configured()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "NOT_FOUND"));
        }
        return ResponseEntity.ok(Map.of("key", key.value()));
    }
}
