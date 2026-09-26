package dev.marwan.console.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.auth.AccessKey;
import dev.marwan.console.state.ClusterStateProvider;
import dev.marwan.console.state.DemoStateProvider;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools an MCP client sees: thin adapters over the services behind the
 * pages, so a tool answers exactly what the site would show.
 */
@Component
public class McpTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DemoStateProvider demo;
    private final ClusterStateProvider cluster;
    private final ConsoleProperties properties;
    private final AccessKey key;

    public McpTools(DemoStateProvider demo, ClusterStateProvider cluster, ConsoleProperties properties,
                    AccessKey key) {
        this.demo = demo;
        this.cluster = cluster;
        this.properties = properties;
        this.key = key;
    }

    public List<SyncToolSpecification> all() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("get_state",
                "The sitting now: seats taken and capacity, the queue (tickets, admitted, waiting), oversold and the "
                        + "admit rate; plus the namespace's pods and CPU budget. Give a drop id for a sandbox, or none "
                        + "for the public sitting.",
                Map.of("drop", str("A sandbox drop id, e.g. d-1a2b3c4d")), List.of(),
                (ex, args) -> json(Map.of("sitting", demo.currentFor(text(args, "drop", properties.canonicalDrop())),
                        "cluster", cluster.current()))));
        return tools;
    }

    // --- plumbing ---

    interface Handler {
        CallToolResult handle(McpSyncServerExchange exchange, Map<String, Object> args) throws Exception;
    }

    static SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
                                      List<String> required, Handler handler) {
        Map<String, Object> schema = Map.of("type", "object", "properties", properties, "required", required);
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder().name(name).description(description).inputSchema(schema).build())
                .callHandler((exchange, request) -> {
                    try {
                        return handler.handle(exchange,
                                request.arguments() == null ? Map.of() : request.arguments());
                    } catch (ToolError e) {
                        return error(e.getMessage());
                    } catch (Exception e) {
                        return error("could not answer: " + e.getMessage());
                    }
                })
                .build();
    }

    static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static String text(Map<String, Object> args, String name, String otherwise) {
        Object v = args.get(name);
        return v == null || v.toString().isBlank() ? otherwise : v.toString();
    }

    boolean keyed(McpSyncServerExchange exchange) {
        Object presented = exchange.transportContext().get(McpConfiguration.KEY);
        return presented != null && key.accepts(presented.toString());
    }

    static CallToolResult json(Object value) {
        return CallToolResult.builder().addTextContent(JSON.writeValueAsString(value)).build();
    }

    static CallToolResult textResult(String value) {
        return CallToolResult.builder().addTextContent(value).build();
    }

    static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    /** A refusal the caller can act on; its message is returned as the tool's error. */
    static class ToolError extends RuntimeException {
        ToolError(String message) {
            super(message);
        }
    }
}
