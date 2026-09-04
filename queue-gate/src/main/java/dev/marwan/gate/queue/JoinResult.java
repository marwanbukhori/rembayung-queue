package dev.marwan.gate.queue;

public record JoinResult(String token, long ticket, long position,
                         double etaSeconds, boolean admitted) { }
