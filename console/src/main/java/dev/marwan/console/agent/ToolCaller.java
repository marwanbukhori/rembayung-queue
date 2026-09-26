package dev.marwan.console.agent;

import tools.jackson.databind.JsonNode;

/** How the run agent asks its questions: over MCP, or in-process when MCP is unavailable. */
public interface ToolCaller {

    /** The fact a call produced, and how it was reached: "mcp" or "in-process". */
    record Call(Fact fact, String via) { }

    Call call(String tool, JsonNode args, RunWindow w, Facts facts);

    /**
     * The only tools a model may reach. Everything else - starting a rush, a
     * fault, a proposal - changes the system, and a model's choice of tool is
     * text it may have been talked into by a log line.
     */
    java.util.Set<String> READ_ONLY = java.util.Set.of("pod_logs", "metric", "events", "pod_status", "endpoints", "get_slo");

    /** A refusal is a fact the model can see, never an exception and never the call. */
    static Call refuse(String tool, Facts facts) {
        return new Call(facts.add("tool: " + tool, tool, "refused: " + tool + " is not a read-only tool; the agent may only read"),
                "refused");
    }

    /** The tools called directly, as before MCP. */
    static ToolCaller inProcess(Tools tools) {
        return (tool, args, w, facts) -> READ_ONLY.contains(tool)
                ? new Call(tools.call(tool, args, w, facts), "in-process") : refuse(tool, facts);
    }
}
