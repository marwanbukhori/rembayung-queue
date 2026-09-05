package dev.marwan.booking.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * What a caller must send to book a seat.
 *
 * Constrained here rather than in the service because these are statements
 * about the request, not about the booking: a party of zero and a missing phone
 * number are malformed input, and the caller needs to hear that as 400 rather
 * than as a server error. The domain keeps its own guards - Slot.takeSeats
 * still refuses a non-positive party - so this is the outer of two checks
 * rather than the only one.
 *
 * @param partySize at least one person, and capped well under a sitting: 20 is
 *                  larger than any table here and small enough that a
 *                  fat-fingered 5000 is refused before it reaches the slot.
 */
public record BookingRequest(
        @NotNull(message = "slotId is required")
        Long slotId,

        @NotBlank(message = "phone is required")
        String phone,

        @Min(value = 1, message = "partySize must be at least 1")
        @Max(value = 20, message = "partySize must be at most 20")
        int partySize,

        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey) { }
