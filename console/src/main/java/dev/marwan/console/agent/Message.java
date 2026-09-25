package dev.marwan.console.agent;

/** One chat message: "system", "user" or "assistant". */
public record Message(String role, String content) { }
