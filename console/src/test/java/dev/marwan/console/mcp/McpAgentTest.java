package dev.marwan.console.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.marwan.console.agent.Facts;
import dev.marwan.console.agent.McpToolCaller;
import dev.marwan.console.agent.RunWindow;
import dev.marwan.console.agent.ToolCaller;
import dev.marwan.console.agent.Tools;
import dev.marwan.console.agent.AgentLoopback;
import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.ops.DropOps;
import dev.marwan.console.ops.LoadOps;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.fabric8.kubernetes.api.model.PodBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "CONSOLE_ACCESS_KEY=s3cret-demo-key")
class McpAgentTest {

    static final RunWindow WINDOW = new RunWindow("load-x", "d", Instant.parse("2026-09-25T13:40:00Z"),
            Instant.parse("2026-09-25T13:45:00Z"));

    @LocalServerPort
    int port;

    @Autowired
    Tools tools;

    @MockitoBean
    ChaosService chaos;

    @MockitoBean
    LoadOps loadOps;

    @MockitoBean
    DropOps dropOps;

    @Autowired
    AgentLoopback loopback;

    @MockitoBean
    ObjectSource source;

    @Test
    void theAgentsToolCallsGoThroughMcpAndGiveTheSameFacts() {
        when(source.pod("booking-service-a")).thenReturn(Optional.of(new PodBuilder().withNewMetadata()
                .withName("booking-service-a").addToLabels("app", "booking-service").endMetadata()
                .withNewStatus().withPhase("Running").endStatus().build()));
        var args = new ObjectMapper().readTree("{\"pod\":\"booking-service-a\"}");
        McpToolCaller mcp = new McpToolCaller("http://localhost:" + port + "/mcp", loopback.token(), tools);

        ToolCaller.Call viaMcp = mcp.call("pod_status", args, WINDOW, new Facts());
        var inProcess = tools.call("pod_status", args, WINDOW, new Facts());

        assertThat(viaMcp.via()).isEqualTo("mcp");
        assertThat(viaMcp.fact().value()).isEqualTo(inProcess.value()).contains("Running");
    }

    @Test
    void anUnreachableServerFallsBackInProcess() {
        var args = new ObjectMapper().readTree("{\"pod\":\"booking-service-a\"}");
        McpToolCaller mcp = new McpToolCaller("http://127.0.0.1:9/mcp", loopback.token(), tools);

        ToolCaller.Call call = mcp.call("pod_status", args, WINDOW, new Facts());

        assertThat(call.via()).isEqualTo("in-process");
        assertThat(call.fact().id()).isEqualTo("F1");
    }

    /** Review C1: a model that asks for a tool that changes things gets a refusal, never the tool. */
    @Test
    void theAgentsCallerRefusesEveryToolThatChangesSomething() {
        var args = new ObjectMapper().readTree("{\"fault\":\"kill-booking-pod\",\"customers\":200}");
        McpToolCaller mcp = new McpToolCaller("http://localhost:" + port + "/mcp", loopback.token(), tools);
        for (String tool : new String[] {"inject_fault", "start_rush", "propose_remediation", "approve_remediation"}) {
            ToolCaller.Call call = mcp.call(tool, args, WINDOW, new Facts());
            assertThat(call.fact().value()).startsWith("refused");
            assertThat(call.via()).isEqualTo("refused");
        }
        verifyNoInteractions(chaos, loadOps, dropOps);
    }

    /** Review C1, second layer: even called directly, the agent's session is not a key holder. */
    @Test
    void theAgentsLoopbackSessionCannotUseKeyedTools() {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder()
                .header(AgentLoopback.HEADER, loopback.token());
        try (McpSyncClient c = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp").requestBuilder(request).build()).build()) {
            c.initialize();
            McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest("inject_fault",
                    java.util.Map.of("fault", "kill-booking-pod")));
            assertThat(r.isError()).isTrue();
            assertThat(((McpSchema.TextContent) r.content().get(0)).text()).contains("console key");
        }
        verify(chaos, never()).inject(anyString());
    }
}
