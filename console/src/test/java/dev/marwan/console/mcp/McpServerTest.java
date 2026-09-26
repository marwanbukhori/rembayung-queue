package dev.marwan.console.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "CONSOLE_ACCESS_KEY=s3cret-demo-key")
class McpServerTest {

    @LocalServerPort
    int port;

    McpSyncClient client() {
        McpSyncClient c = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp").build()).requestTimeout(Duration.ofSeconds(10)).build();
        c.initialize();
        return c;
    }

    @Test
    void aClientConnectsAndSeesTheConsole() {
        try (McpSyncClient c = client()) {
            assertThat(c.getServerInfo().name()).isEqualTo("rembayung-console");
            assertThat(c.listTools().tools()).extracting(McpSchema.Tool::name).isNotEmpty();
        }
    }

    @Test
    void twoClientsHaveTheirOwnSessions() {
        try (McpSyncClient a = client(); McpSyncClient b = client()) {
            assertThat(a.listTools().tools()).hasSameSizeAs(b.listTools().tools());
        }
    }
}
