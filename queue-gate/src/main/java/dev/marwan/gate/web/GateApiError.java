package dev.marwan.gate.web;

import java.util.Map;

public record GateApiError(String reason, Map<String, Object> details) { }
