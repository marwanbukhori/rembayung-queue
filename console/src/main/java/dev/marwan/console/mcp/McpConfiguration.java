package dev.marwan.console.mcp;

import java.util.Map;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.marwan.console.auth.KeyFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * The console as an MCP server, at /mcp beside the API.
 *
 * Built on the official Java SDK's servlet transport rather than a framework
 * starter: one servlet, one server, and the tools as plain functions over the
 * services the pages already use. The key a caller sent is read from the HTTP
 * request into the transport context, so each tool decides for itself what
 * needs it - the same rules as the site, enforced in one place per tool.
 */
@Configuration
public class McpConfiguration {

    static final String ENDPOINT = "/mcp";
    static final String KEY = "consoleKey";

    @Bean
    McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(JsonMapper.builder().build());
    }

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport(McpJsonMapper json) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(json)
                .mcpEndpoint(ENDPOINT)
                .contextExtractor(request -> {
                    String key = request.getHeader(KeyFilter.HEADER);
                    return McpTransportContext.create(key == null ? Map.of() : Map.of(KEY, key));
                })
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, ENDPOINT, ENDPOINT + "/*");
    }

    @Bean(destroyMethod = "close")
    McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport, McpJsonMapper json,
                            McpTools tools) {
        return McpServer.sync(transport)
                .jsonMapper(json)
                .serverInfo("rembayung-console", "1")
                .instructions("A live virtual queue and booking service on OpenShift. Read the state, the "
                        + "objects and the run agent's reports; with the console key (X-Console-Key header), "
                        + "start a rush. Get the demo key from /api/demo-key.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.all())
                .build();
    }
}
