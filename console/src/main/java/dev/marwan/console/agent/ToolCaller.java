package dev.marwan.console.agent;

import tools.jackson.databind.JsonNode;

/** How the run agent asks its questions: over MCP, or in-process when MCP is unavailable. */
public interface ToolCaller {

    /** The fact a call produced, and how it was reached: "mcp" or "in-process". */
    record Call(Fact fact, String via) { }

    Call call(String tool, JsonNode args, RunWindow w, Facts facts);

    /** The tools called directly, as before MCP. */
    static ToolCaller inProcess(Tools tools) {
        return (tool, args, w, facts) -> new Call(tools.call(tool, args, w, facts), "in-process");
    }
}
