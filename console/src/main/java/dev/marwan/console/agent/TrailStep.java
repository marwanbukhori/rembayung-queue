package dev.marwan.console.agent;

/** One tool call: what was asked, the reason the model gave, and the fact it produced. */
public record TrailStep(String tool, String args, String why, String factId, String via) {

    /** A step from before MCP, which did not record how it was reached. */
    public TrailStep(String tool, String args, String why, String factId) {
        this(tool, args, why, factId, null);
    }
}
