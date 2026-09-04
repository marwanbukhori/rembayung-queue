package dev.marwan.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.Instant;

@ConfigurationProperties(prefix = "drop")
public record DropProperties(
        Instant opensAt,
        Instant closesAt,
        int seats,
        int ticketCap,
        int admitRate,
        Duration admissionWindow,
        Duration ticketTtl) { }
