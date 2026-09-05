package dev.marwan.gate.web;

import dev.marwan.gate.queue.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GateExceptionHandler {

    @ExceptionHandler(DropNotOpenException.class)
    public ResponseEntity<GateApiError> notOpen(DropNotOpenException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getSecondsUntilOpen()))
                .body(new GateApiError("NOT_OPEN",
                        Map.of("secondsUntilOpen", e.getSecondsUntilOpen())));
    }

    @ExceptionHandler(DropClosedException.class)
    public ResponseEntity<GateApiError> closed(DropClosedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new GateApiError("DROP_CLOSED", Map.of()));
    }

    @ExceptionHandler(SoldOutException.class)
    public ResponseEntity<GateApiError> soldOut(SoldOutException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new GateApiError("SOLD_OUT", Map.of()));
    }

    /**
     * 404 rather than 400: a drop is a resource that has expired or never
     * existed, and a visitor returning to a stale bookmark should be told the
     * drop is gone rather than that their request was malformed.
     */
    @ExceptionHandler(UnknownDropException.class)
    public ResponseEntity<GateApiError> unknownDrop(UnknownDropException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new GateApiError("UNKNOWN_DROP", Map.of()));
    }

    @ExceptionHandler(TokenRejectedException.class)
    public ResponseEntity<GateApiError> tokenRejected(TokenRejectedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new GateApiError(e.getReason(), Map.of()));
    }

    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    public ResponseEntity<GateApiError> downstreamUnavailable(
            org.springframework.web.client.ResourceAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(new GateApiError("BOOKING_SERVICE_UNAVAILABLE", Map.of()));
    }
}
