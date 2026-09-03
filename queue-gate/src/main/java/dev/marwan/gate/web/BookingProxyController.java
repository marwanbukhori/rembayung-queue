package dev.marwan.gate.web;

import dev.marwan.gate.queue.AdmissionService;
import dev.marwan.gate.queue.TokenRejectedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
public class BookingProxyController {

    private final AdmissionService admissionService;
    private final BookingClient bookingClient;

    public BookingProxyController(AdmissionService admissionService, BookingClient bookingClient) {
        this.admissionService = admissionService;
        this.bookingClient = bookingClient;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "X-Admission-Token", required = false) String token,
            @RequestBody String body) {

        if (token == null || token.isBlank()) {
            throw new TokenRejectedException("TOKEN_INVALID");
        }
        admissionService.consume(token);

        ResponseEntity<String> downstream = bookingClient.createBooking(body);
        return ResponseEntity.status(downstream.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(downstream.getBody());
    }

    /**
     * Deposit carries no admission token: the token was consumed by the booking
     * itself. This endpoint is therefore unauthenticated and takes a guessable
     * sequential id — a known weakness recorded in the design spec, acceptable
     * only because payment is mocked.
     */
    @PostMapping("/{id}/deposit")
    public ResponseEntity<String> deposit(@PathVariable long id) {
        ResponseEntity<String> downstream = bookingClient.confirmDeposit(id);
        return ResponseEntity.status(downstream.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(downstream.getBody());
    }
}
