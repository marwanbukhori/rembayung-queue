package dev.marwan.console.incident;

import java.time.Instant;

/** One line of an incident's timeline. {@code source}: chaos, slo, kubernetes, agent, human or action. */
public record IncidentEvent(Instant at, String source, String text) { }
