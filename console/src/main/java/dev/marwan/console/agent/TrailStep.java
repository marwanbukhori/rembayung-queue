package dev.marwan.console.agent;

/** One tool call: what was asked, the reason the model gave, and the fact it produced. */
public record TrailStep(String tool, String args, String why, String factId) { }
