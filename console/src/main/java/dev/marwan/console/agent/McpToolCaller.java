package dev.marwan.console.agent;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marwan.console.auth.KeyFilter;
import dev.marwan.console.objects.LogLines;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The run agent as an MCP client of its own console.
 *
 * It asks its five questions through /mcp over loopback - the same endpoint,
 * the same tools and the same answers an outside client such as Claude Code
 * gets - so there is one tool set, not an internal one and a published one
 * that can drift apart. The run window travels as the tools' from/to.
 *
 * Any failure to reach the server hands that call to the in-process tools and
 * says so in the trail, so a transport problem never costs a run its report.
 */
public class McpToolCaller implements ToolCaller {

    private static final Logger log = LoggerFactory.getLogger(McpToolCaller.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String endpoint;
    private final String key;
    private final Tools fallback;
    private McpSyncClient client;

    public McpToolCaller(String endpoint, String key, Tools fallback) {
        this.endpoint = endpoint;
        this.key = key;
        this.fallback = fallback;
    }

    @Override
    public synchronized Call call(String tool, JsonNode args, RunWindow w, Facts facts) {
        try {
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (args != null && args.isObject()) {
                arguments.putAll(JSON.convertValue(args, Map.class));
            }
            arguments.put("from", w.start().toString());
            arguments.put("to", w.end().toString());
            McpSchema.CallToolResult result = client().callTool(new McpSchema.CallToolRequest(tool, arguments));
            String text = result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .collect(Collectors.joining("\n"));
            String bounded = text.length() > Tools.MAX_VALUE ? text.substring(0, Tools.MAX_VALUE) + "…" : text;
            Fact fact = facts.add("tool: " + tool, LogLines.mask(tool + "(" + (args == null ? "" : args) + ")"),
                    LogLines.mask(bounded));
            return new Call(fact, "mcp");
        } catch (RuntimeException e) {
            log.warn("MCP call {} failed, answering in-process: {}", tool, e.getMessage());
            reset();
            return new Call(fallback.call(tool, args, w, facts), "in-process");
        }
    }

    private McpSyncClient client() {
        if (client == null) {
            int slash = endpoint.indexOf('/', endpoint.indexOf("//") + 2);
            HttpRequest.Builder request = HttpRequest.newBuilder();
            if (key != null) {
                request.header(KeyFilter.HEADER, key);
            }
            McpSyncClient c = McpClient.sync(HttpClientStreamableHttpTransport
                            .builder(endpoint.substring(0, slash))
                            .endpoint(endpoint.substring(slash))
                            .connectTimeout(Duration.ofSeconds(2))
                            .requestBuilder(request)
                            .build())
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            c.initialize();
            client = c;
        }
        return client;
    }

    private void reset() {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (RuntimeException ignored) {
                // Already broken; the next call opens a new session.
            }
            client = null;
        }
    }
}
