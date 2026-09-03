package dev.marwan.booking.web;

import dev.marwan.booking.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(SlotSoldOutException.class)
    public ResponseEntity<ApiError> soldOut(SlotSoldOutException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                "SLOT_SOLD_OUT",
                Map.of("slotId", e.getSlotId(),
                       "requested", e.getRequested(),
                       "remaining", e.getRemaining())));
    }

    @ExceptionHandler(SlotNotFoundException.class)
    public ResponseEntity<ApiError> slotMissing(SlotNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                "SLOT_NOT_FOUND", Map.of("slotId", e.getSlotId())));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ApiError> bookingMissing(BookingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                "BOOKING_NOT_FOUND", Map.of("bookingId", e.getBookingId())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> notPending(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                "BOOKING_NOT_PENDING", Map.of("message", e.getMessage())));
    }

    /**
     * The connection pool is saturated. This is back-pressure, not a fault:
     * the database is ~250ms away, a booking holds a connection for ~2.7s, and
     * 20 connections cap throughput near 7/sec. Telling the caller to retry is
     * truthful; a 500 is not.
     */
    @ExceptionHandler(org.springframework.transaction.CannotCreateTransactionException.class)
    public ResponseEntity<ApiError> overloaded(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(new ApiError("BOOKING_SERVICE_BUSY", Map.of()));
    }
}
