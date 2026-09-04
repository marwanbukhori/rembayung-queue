package dev.marwan.booking.api;

public record BookingRequest(Long slotId, String phone, int partySize, String idempotencyKey) { }
