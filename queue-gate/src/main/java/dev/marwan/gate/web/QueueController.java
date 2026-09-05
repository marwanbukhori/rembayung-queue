package dev.marwan.gate.web;

import dev.marwan.gate.queue.AdmissionService;
import dev.marwan.gate.queue.DropRegistry;
import dev.marwan.gate.queue.JoinResult;
import dev.marwan.gate.queue.PositionView;
import dev.marwan.gate.queue.QueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/queue")
public class QueueController {

    private final QueueService queueService;
    private final AdmissionService admissionService;

    public QueueController(QueueService queueService, AdmissionService admissionService) {
        this.queueService = queueService;
        this.admissionService = admissionService;
    }

    @PostMapping
    public JoinResult join(
            // Optional so every existing caller, the load test and open-drop.sh
            // included, keeps working against the canonical drop untouched.
            @RequestParam(name = "drop", defaultValue = DropRegistry.DEFAULT_ID) String dropId) {
        return queueService.join(dropId);
    }

    @GetMapping("/{token}")
    public ResponseEntity<PositionView> position(@PathVariable String token) {
        return admissionService.position(token)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
