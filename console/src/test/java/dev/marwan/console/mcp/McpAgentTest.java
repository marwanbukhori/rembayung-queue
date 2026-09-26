package dev.marwan.console.mcp;

import static org.assertj.core.api.Assertions.assertThat;
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
import dev.marwan.console.objects.ObjectSource;
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
    ObjectSource source;

    @Test
    void theAgentsToolCallsGoThroughMcpAndGiveTheSameFacts() {
        when(source.pod("booking-service-a")).thenReturn(Optional.of(new PodBuilder().withNewMetadata()
                .withName("booking-service-a").addToLabels("app", "booking-service").endMetadata()
                .withNewStatus().withPhase("Running").endStatus().build()));
        var args = new ObjectMapper().readTree("{\"pod\":\"booking-service-a\"}");
        McpToolCaller mcp = new McpToolCaller("http://localhost:" + port + "/mcp", "s3cret-demo-key", tools);

        ToolCaller.Call viaMcp = mcp.call("pod_status", args, WINDOW, new Facts());
        var inProcess = tools.call("pod_status", args, WINDOW, new Facts());

        assertThat(viaMcp.via()).isEqualTo("mcp");
        assertThat(viaMcp.fact().value()).isEqualTo(inProcess.value()).contains("Running");
    }

    @Test
    void anUnreachableServerFallsBackInProcess() {
        var args = new ObjectMapper().readTree("{\"pod\":\"booking-service-a\"}");
        McpToolCaller mcp = new McpToolCaller("http://127.0.0.1:9/mcp", "s3cret-demo-key", tools);

        ToolCaller.Call call = mcp.call("pod_status", args, WINDOW, new Facts());

        assertThat(call.via()).isEqualTo("in-process");
        assertThat(call.fact().id()).isEqualTo("F1");
    }
}
