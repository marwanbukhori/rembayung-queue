package dev.marwan.booking.api;

import java.util.Map;

public record ApiError(String reason, Map<String, Object> details) { }
