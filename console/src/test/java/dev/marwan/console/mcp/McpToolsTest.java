package dev.marwan.console.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.marwan.console.agent.Analysis;
import dev.marwan.console.agent.AnalysisStore;
import dev.marwan.console.agent.Claim;
import dev.marwan.console.agent.Fact;
import dev.marwan.console.agent.Facts;
import dev.marwan.console.agent.Report;
import dev.marwan.console.agent.Tools;
import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.incident.Incident;
import dev.marwan.console.incident.IncidentStore;
import dev.marwan.console.incident.IncidentWatcher;
import dev.marwan.console.incident.Remediation;
import dev.marwan.console.slo.SloReading;
import dev.marwan.console.slo.SloService;
import dev.marwan.console.objects.LogPage;
import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.objects.ObjectsProvider;
import dev.marwan.console.objects.PodLogs;
import dev.marwan.console.ops.DropOps;
import dev.marwan.console.ops.LoadOps;
import dev.marwan.console.ops.LoadRun;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "CONSOLE_ACCESS_KEY=s3cret-demo-key")
class McpToolsTest {

    @LocalServerPort
    int port;

    @MockitoBean AnalysisStore store;
    @MockitoBean ObjectsProvider objects;
    @MockitoBean PodLogs podLogs;
    @MockitoBean DropOps dropOps;
    @MockitoBean LoadOps loadOps;
    @MockitoBean Tools tools;
    @MockitoBean ObjectSource source;
    @MockitoBean SloService slo;
    @MockitoBean IncidentStore incidents;
    @MockitoBean IncidentWatcher watcher;
    @MockitoBean ChaosService chaos;
    @MockitoBean Remediation remediation;

    McpSyncClient client(String key) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        if (key != null) {
            request.header("X-Console-Key", key);
        }
        McpSyncClient c = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp").requestBuilder(request).build()).requestTimeout(Duration.ofSeconds(10)).build();
        c.initialize();
        return c;
    }

    static String text(CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    static CallToolResult call(McpSyncClient c, String tool, Map<String, Object> args) {
        return c.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    static Analysis analysis() {
        Instant t = Instant.parse("2026-09-25T10:00:00Z");
        return new Analysis("load-a", "d", t, t.plusSeconds(60),
                List.of(new Fact("F1", "k6", "Arrived", "200"), new Fact("F2", "tool: pod_logs", "pod_logs({})", "raw stack line")),
                List.of(), Report.sections(List.of(new Claim("Fine.", List.of("F1"))), List.of(), List.of(), List.of(), List.of()),
                "m", "model", null, List.of(), t.plusSeconds(90), 1000);
    }

    @Test
    void listsTheFifteenToolsAndNoneOfThemApproves() {
        try (McpSyncClient c = client("s3cret-demo-key")) {
            List<String> names = c.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertThat(names).containsExactlyInAnyOrder(
                    "get_state", "list_runs", "get_report", "describe_object", "metric", "events", "pod_status",
                    "endpoints", "pod_logs", "start_rush",
                    "get_slo", "list_incidents", "get_incident", "inject_fault", "propose_remediation");
            assertThat(names).noneMatch(n -> n.contains("approve") || n.contains("apply") || n.contains("dismiss"));
        }
    }

    static Incident openIncident() {
        Incident i = new Incident();
        i.id = "inc-1";
        i.kind = "drill";
        i.fault = "squeeze-pool";
        i.status = "open";
        i.openedAt = Instant.parse("2026-09-26T12:00:00Z");
        return i;
    }

    @Test
    void sloAndIncidentsAreReadableWithoutTheKey() {
        Instant t = Instant.parse("2026-09-26T12:00:00Z");
        when(slo.now()).thenReturn(new SloReading(t, true, null, true, 0.93, 2.6));
        when(slo.history(any())).thenReturn(List.of());
        when(incidents.list()).thenReturn(List.of(openIncident()));
        when(incidents.get("inc-1")).thenReturn(Optional.of(openIncident()));
        try (McpSyncClient c = client(null)) {
            assertThat(text(call(c, "get_slo", Map.of()))).contains("0.93").contains("breached");
            assertThat(text(call(c, "list_incidents", Map.of()))).contains("inc-1");
            assertThat(text(call(c, "get_incident", Map.of("id", "inc-1")))).contains("squeeze-pool");
            assertThat(call(c, "get_incident", Map.of("id", "nope")).isError()).isTrue();
        }
    }

    @Test
    void injectingAFaultNeedsTheKeyAndOneAtATime() {
        Instant t = Instant.parse("2026-09-26T12:00:00Z");
        when(chaos.inject("squeeze-pool")).thenReturn(new ChaosService.ActiveFault("squeeze-pool", t, t.plusSeconds(120)))
                .thenThrow(new ChaosService.Busy(new ChaosService.ActiveFault("squeeze-pool", t, t.plusSeconds(120))));
        try (McpSyncClient open = client(null); McpSyncClient keyed = client("s3cret-demo-key")) {
            CallToolResult refused = call(open, "inject_fault", Map.of("fault", "squeeze-pool"));
            assertThat(refused.isError()).isTrue();
            assertThat(text(refused)).contains("/api/demo-key");
            verify(chaos, never()).inject(anyString());

            assertThat(call(keyed, "inject_fault", Map.of("fault", "squeeze-pool")).isError()).isFalse();
            CallToolResult busy = call(keyed, "inject_fault", Map.of("fault", "squeeze-pool"));
            assertThat(busy.isError()).isTrue();
            assertThat(text(busy)).contains("squeeze-pool is running");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProposalStaysPendingAndNothingIsApplied() {
        Incident incident = openIncident();
        when(incidents.open()).thenReturn(Optional.of(incident));
        when(incidents.get("inc-1")).thenReturn(Optional.of(incident));
        when(watcher.update(eq("inc-1"), any())).thenAnswer(inv -> {
            ((Consumer<Incident>) inv.getArgument(1)).accept(incident);
            return Optional.of(incident);
        });
        try (McpSyncClient open = client(null); McpSyncClient keyed = client("s3cret-demo-key")) {
            assertThat(call(open, "propose_remediation", Map.of("action", "restart-booking", "reason", "r")).isError())
                    .isTrue();
            assertThat(call(keyed, "propose_remediation", Map.of("action", "delete-everything", "reason", "r")).isError())
                    .isTrue();
            // Review minor 11 and I3: a malformed or out-of-range count is a tool error, not a crash or a proposal.
            CallToolResult garbled = call(keyed, "propose_remediation",
                    Map.of("action", "scale-booking", "replicas", "2.5x", "reason", "r"));
            assertThat(garbled.isError()).isTrue();
            assertThat(text(garbled)).contains("replicas");
            assertThat(call(keyed, "propose_remediation",
                    Map.of("action", "scale-booking", "replicas", 1, "reason", "r")).isError()).isTrue();
            assertThat(incident.proposals).isEmpty();
            CallToolResult filed = call(keyed, "propose_remediation",
                    Map.of("action", "scale-booking", "replicas", 3, "reason", "the pool is saturated"));
            assertThat(filed.isError()).isFalse();
            assertThat(text(filed)).contains("pending").contains("approve");
            assertThat(incident.proposals).singleElement().satisfies(p -> {
                assertThat(p.status()).isEqualTo("pending");
                assertThat(p.replicas()).isEqualTo(3);
            });
            CallToolResult second = call(keyed, "propose_remediation", Map.of("action", "restart-booking", "reason", "r"));
            assertThat(second.isError()).isTrue();
            assertThat(incident.proposals).hasSize(1);
        }
        verifyNoInteractions(remediation);
    }

    @Test
    void runsAndReportsAreReadableAndRawLogsInThemNeedTheKey() {
        when(store.list()).thenReturn(List.of(analysis()));
        when(store.get("load-a")).thenReturn(Optional.of(analysis()));
        try (McpSyncClient open = client(null); McpSyncClient keyed = client("s3cret-demo-key")) {
            assertThat(text(call(open, "list_runs", Map.of()))).contains("\"customers\":200");
            assertThat(text(call(open, "get_report", Map.of("run", "load-a")))).doesNotContain("raw stack line");
            assertThat(text(call(keyed, "get_report", Map.of("run", "load-a")))).contains("raw stack line");
            assertThat(call(open, "get_report", Map.of("run", "nope")).isError()).isTrue();
        }
    }

    @Test
    void theClusterToolsGoThroughTheAgentsToolsWithAWindow() {
        when(tools.call(eq("metric"), any(), any(), any(Facts.class)))
                .thenAnswer(i -> ((Facts) i.getArgument(3)).add("tool: metric", "metric", "queue-gate (pods):2,6"));
        try (McpSyncClient c = client(null)) {
            assertThat(text(call(c, "metric", Map.of("chart", "replicas")))).isEqualTo("queue-gate (pods):2,6");
            CallToolResult tooLong = call(c, "metric", Map.of("chart", "replicas",
                    "from", "2026-09-25T00:00:00Z", "to", "2026-09-25T05:00:00Z"));
            assertThat(tooLong.isError()).isFalse();
        }
    }

    @Test
    void podLogsWithoutTheKeyAreAppEventsOnly() {
        when(podLogs.read(eq("booking-service-a"), any(), eq("events"), eq(false)))
                .thenReturn(new LogPage("booking-service-a", true, null, "events", true, null, List.of(), null));
        when(tools.call(eq("pod_logs"), any(), any(), any(Facts.class)))
                .thenAnswer(i -> ((Facts) i.getArgument(3)).add("tool: pod_logs", "pod_logs", "13:40:10 WARN raw"));
        try (McpSyncClient open = client(null); McpSyncClient keyed = client("s3cret-demo-key")) {
            assertThat(text(call(open, "pod_logs", Map.of("pod", "booking-service-a")))).doesNotContain("WARN raw");
            assertThat(text(call(keyed, "pod_logs", Map.of("pod", "booking-service-a")))).contains("WARN raw");
        }
    }

    @Test
    void startingARushNeedsTheKeyAndNoRunAlreadyGoing() {
        when(source.jobs()).thenReturn(List.of());
        when(dropOps.create(any())).thenReturn(new DropOps.Sandbox("d-new", 5, 8));
        when(loadOps.start(eq("d-new"), any())).thenReturn(LoadRun.none("d-new", "load-d-new"));
        try (McpSyncClient open = client(null); McpSyncClient keyed = client("s3cret-demo-key")) {
            CallToolResult refused = call(open, "start_rush", Map.of("customers", 200));
            assertThat(refused.isError()).isTrue();
            assertThat(text(refused)).contains("/api/demo-key");
            verify(dropOps, never()).create(any());

            CallToolResult started = call(keyed, "start_rush", Map.of("customers", 200, "waves", 2, "admit_rate", 8));
            assertThat(started.isError()).isFalse();
            assertThat(text(started)).contains("d-new");

            when(source.jobs()).thenReturn(List.of(new JobBuilder().withNewMetadata().withName("load-d-x")
                    .addToLabels("app", "rembayung-load").endMetadata().withNewStatus().withActive(1).endStatus().build()));
            CallToolResult busy = call(keyed, "start_rush", Map.of("customers", 200));
            assertThat(busy.isError()).isTrue();
            assertThat(text(busy)).contains("already");
        }
    }

    @Test
    void anUnknownObjectIsAToolErrorNotACrash() {
        when(objects.describe(anyString(), anyString())).thenThrow(new IllegalArgumentException("unknown kind: bogus"));
        try (McpSyncClient c = client(null)) {
            assertThat(call(c, "describe_object", Map.of("kind", "bogus", "name", "x")).isError()).isTrue();
        }
    }
}
