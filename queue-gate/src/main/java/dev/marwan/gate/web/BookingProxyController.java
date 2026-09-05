package dev.marwan.gate.web;

import dev.marwan.gate.queue.AdmissionService;
import dev.marwan.gate.queue.DropRecord;
import dev.marwan.gate.queue.TokenRejectedException;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
public class BookingProxyController {

    private final AdmissionService admissionService;
    private final BookingClient bookingClient;

    /** tools.jackson, not com.fasterxml — Boot 4.1.1 ships Jackson 3 and the
     *  Jackson 2 import compiles then fails at runtime. */
    private static final ObjectMapper JSON = new ObjectMapper();

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
        DropRecord drop = admissionService.consume(token);

        // The slot comes from the TOKEN, not the request body.
        //
        // This forwarded the body verbatim, so slotId was whatever the caller
        // typed. A visitor holding a perfectly valid token for their own sandbox
        // could change one JSON field and book against slot 1 — the restaurant's
        // real 250 seats — and every check upstream would pass, because the token
        // genuinely was admitted. It just was not admitted for THAT slot.
        //
        // Overriding rather than validating-and-rejecting: there is no legitimate
        // reason for a client to choose, and a 400 would only teach an attacker
        // which value to try next.
        ResponseEntity<String> downstream =
                bookingClient.createBooking(withSlotFrom(drop, body));
        return ResponseEntity.status(downstream.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(downstream.getBody());
    }

    /**
     * Replaces slotId in the request body with the drop's own.
     *
     * The canonical drop carries no slot of its own — it books slot 1, which the
     * body already names — so a null slotId leaves the body untouched and the
     * 21:00 path behaves exactly as before.
     */
    private String withSlotFrom(DropRecord drop, String body) {
        if (drop.slotId() == null) {
            return body;
        }
        try {
            var node = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
            node.put("slotId", drop.slotId());
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            // A body we cannot parse is one booking-service will reject anyway;
            // forwarding it unchanged keeps the error where it belongs rather
            // than turning a client's malformed JSON into a gate error.
            return body;
        }
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
