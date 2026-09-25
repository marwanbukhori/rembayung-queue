package dev.marwan.console.agent;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The run agent's settings. Off unless switched on, so a console run outside
 * the cluster - or in a test - never starts analysing a namespace it cannot see.
 */
@ConfigurationProperties(prefix = "console.agent")
public record AgentProperties(boolean enabled, String baseUrl, String model, Duration interval) { }
