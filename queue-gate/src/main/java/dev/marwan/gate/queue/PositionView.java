package dev.marwan.gate.queue;

public record PositionView(long position, boolean admitted, long expiresInSeconds) { }
