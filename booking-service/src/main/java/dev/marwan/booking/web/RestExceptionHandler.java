package dev.marwan.booking.web;

import dev.marwan.booking.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
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

    /**
     * A request the constraints on BookingRequest refused.
     *
     * Mapped here rather than left to Spring's default so a rejected booking
     * reads like every other error this API returns: the same ApiError shape,
     * with one entry per field that failed. Without it the caller gets a
     * ProblemDetail for validation and an ApiError for everything else, and has
     * to parse both.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError field : e.getBindingResult().getFieldErrors()) {
            // First message wins: one line per field reads better than a list,
            // and a field carrying two failures is telling the caller one thing.
            fields.putIfAbsent(field.getField(), field.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", fields));
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
     *
     * Two exception types, because they are NOT related and an earlier version of
     * this comment claimed they were.
     *
     * The claim was that DataAccessResourceFailureException is the superclass of
     * CannotCreateTransactionException. It is not. Verified against the running
     * classpath:
     *
     *   CannotCreateTransactionException
     *     -> org.springframework.transaction.TransactionException
     *     -> org.springframework.core.NestedRuntimeException
     *
     * It lives in org.springframework.transaction and shares no ancestor with
     * org.springframework.dao below RuntimeException. So this handler caught
     * CannotGetJdbcConnectionException — thrown when code already inside a
     * transaction asks for a connection — and completely missed the one Spring
     * actually throws when @Transactional cannot obtain a connection to OPEN the
     * transaction, which is the overwhelmingly common case under pool exhaustion.
     *
     * The cost was not theoretical. The first load run ever executed inside the
     * cluster returned HTTP 500 for 188 of 200 bookings, while loadtest/drop.js
     * and the project's notes both stated that overload produces 503 with
     * Retry-After. No test caught it because nothing in the suite exhausts a
     * pool, and no laptop run had ever pushed hard enough.
     */
    @ExceptionHandler({
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class
    })
    public ResponseEntity<ApiError> overloaded(RuntimeException e) {
        // Check if this is a connection pool timeout by examining the cause chain
        if (isSQLTransientConnectionException(e)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "2")
                    .body(new ApiError("BOOKING_SERVICE_BUSY", Map.of()));
        }
        // Not a pool timeout, re-throw to use default error handling
        throw e;
    }

    private boolean isSQLTransientConnectionException(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
