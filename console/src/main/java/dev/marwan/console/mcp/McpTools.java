package dev.marwan.console.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.agent.AnalysesController;
import dev.marwan.console.agent.AnalysisStore;
import dev.marwan.console.agent.Facts;
import dev.marwan.console.agent.RunWindow;
import dev.marwan.console.agent.Tools;
import dev.marwan.console.auth.AccessKey;
import dev.marwan.console.objects.LogLine;
import dev.marwan.console.objects.LogPage;
import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.objects.ObjectsProvider;
import dev.marwan.console.objects.PodLogs;
import dev.marwan.console.ops.DropOps;
import dev.marwan.console.ops.LoadOps;
import dev.marwan.console.ops.LoadRun;
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

    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);
    static final Duration MAX_WINDOW = Duration.ofMinutes(60);
    static final int LOG_LINES = 40;
    static final String NEEDS_KEY = "This needs the console key, sent as the X-Console-Key header. "
            + "The demo key is public: GET /api/demo-key on this console.";

    private final DemoStateProvider demo;
    private final ClusterStateProvider cluster;
    private final ConsoleProperties properties;
    private final AccessKey key;
    private final AnalysisStore store;
    private final ObjectsProvider objects;
    private final PodLogs podLogs;
    private final Tools tools;
    private final ObjectSource source;
    private final DropOps dropOps;
    private final LoadOps loadOps;
    private final Clock clock;

    public McpTools(DemoStateProvider demo, ClusterStateProvider cluster, ConsoleProperties properties,
                    AccessKey key, AnalysisStore store, ObjectsProvider objects, PodLogs podLogs, Tools tools,
                    ObjectSource source, DropOps dropOps, LoadOps loadOps, Clock clock) {
        this.demo = demo;
        this.cluster = cluster;
        this.properties = properties;
        this.key = key;
        this.store = store;
        this.objects = objects;
        this.podLogs = podLogs;
        this.tools = tools;
        this.source = source;
        this.dropOps = dropOps;
        this.loadOps = loadOps;
        this.clock = clock;
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
        tools.add(tool("list_runs",
                "Every rush the run agent has analysed, newest first: when, customers, booked, seats, oversold, "
                        + "one or two waves, and whether the model or the facts wrote the report.",
                Map.of(), List.of(),
                (ex, args) -> json(store.list().stream().map(AnalysesController::summary).toList())));
        tools.add(tool("get_report",
                "One analysed run in full: its numbered facts, its report sections with the facts each claim cites, "
                        + "and what the agent looked at. Raw log lines in it need the console key.",
                Map.of("run", str("A run key or job name from list_runs")), List.of("run"),
                (ex, args) -> {
                    String run = text(args, "run", null);
                    var a = store.get(run).orElseThrow(() -> new ToolError("no analysed run " + run
                            + "; list_runs shows the ones kept"));
                    return json(keyed(ex) ? a : AnalysesController.withoutRawLogs(a));
                }));
        tools.add(tool("describe_object",
                "What the inspector shows for one object: Route, Service, Deployment, HPA, Pod, Job or CronJob - "
                        + "its headline, facts, related objects and recent events.",
                Map.of("kind", str("route, service, deployment, hpa, pod, job or cronjob"), "name", str("Its name")),
                List.of("kind", "name"),
                (ex, args) -> {
                    try {
                        return json(objects.describe(text(args, "kind", ""), text(args, "name", "")));
                    } catch (RuntimeException e) {
                        throw new ToolError("could not describe it: " + e.getMessage());
                    }
                }));
        Map<String, Object> window = Map.of("from", str("ISO time; default 15 minutes ago"),
                "to", str("ISO time; default now. Windows over 60 minutes are shortened."));
        tools.add(cluster("metric", "One of the four charts - requests, latency, pool or replicas - over a window, "
                + "30 points a series.", merge(Map.of("chart", str("requests, latency, pool or replicas"),
                "pod", str("Optional: one pod or series")), window), List.of("chart")));
        tools.add(cluster("events", "Kubernetes events for one object in a window, up to 20.",
                merge(Map.of("kind", str("Deployment, Pod, HorizontalPodAutoscaler, Job or Service"),
                        "name", str("Its name")), window), List.of("kind", "name")));
        tools.add(cluster("pod_status", "A pod's phase, readiness, restarts, node and owning ReplicaSet.",
                Map.of("pod", str("The pod's name")), List.of("pod")));
        tools.add(cluster("endpoints", "The pods ready behind a Service right now.",
                Map.of("service", str("The Service's name")), List.of("service")));
        tools.add(tool("pod_logs",
                "A pod's log lines in a window, up to 40, phone numbers masked. Without the console key only the "
                        + "app's structured events are returned, as on the site; with it, raw lines.",
                merge(Map.of("pod", str("The pod's name"), "level", str("Optional: WARN, ERROR or INFO"),
                        "contains", str("Optional text the line must contain")), window), List.of("pod"),
                (ex, args) -> {
                    if (keyed(ex)) {
                        return clusterCall("pod_logs", args);
                    }
                    LogPage page = podLogs.read(text(args, "pod", ""), null, "events", false);
                    List<LogLine> lines = page.lines() == null ? List.of() : page.lines();
                    String out = lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()).stream()
                            .map(l -> l.at() + " " + (l.event() == null ? "" : l.event() + " ") + l.message())
                            .collect(Collectors.joining("\n"));
                    return textResult((out.isEmpty() ? "no app events" : out)
                            + "\n(app events only - raw lines need the console key)");
                }));
        tools.add(tool("start_rush",
                "Start a rush on a fresh sandbox sitting: customers arriving at once, one wave or two waves three "
                        + "minutes apart, at an admit rate of 1, 8 or 200 per second. Needs the console key. Refused "
                        + "while another rush is running. The run agent reports on it about a minute after it ends.",
                Map.of("customers", integer("How many arrive at once, 1 to 5000; default 60"),
                        "waves", integer("1 (default) or 2"), "admit_rate", integer("1 (default), 8 or 200")),
                List.of("customers"),
                (ex, args) -> {
                    if (!keyed(ex)) {
                        throw new ToolError(NEEDS_KEY);
                    }
                    boolean live = source.jobs().stream().anyMatch(j -> j.getMetadata().getLabels() != null
                            && "rembayung-load".equals(j.getMetadata().getLabels().get("app"))
                            && j.getStatus() != null && j.getStatus().getActive() != null && j.getStatus().getActive() > 0);
                    if (live) {
                        throw new ToolError("a rush is already running; wait for it to finish, then try again");
                    }
                    int waves = Integer.parseInt(text(args, "waves", "1"));
                    DropOps.Sandbox sitting = dropOps.create(
                            new DropOps.StartDrop(Integer.parseInt(text(args, "admit_rate", "1"))));
                    LoadRun run = loadOps.start(sitting.dropId(), new LoadOps.SendLoad(
                            Integer.parseInt(text(args, "customers", "60")), waves));
                    return json(Map.of("dropId", sitting.dropId(), "job", String.valueOf(run.jobName()),
                            "waves", waves, "admitRate", sitting.admitRate(),
                            "takes", waves == 2 ? "about 5 minutes" : "about 2 minutes",
                            "then", "list_runs shows the agent's report about a minute after it ends"));
                }));
        return tools;
    }

    /** A tool answered by the run agent's own read-only tools, over a window. */
    private SyncToolSpecification cluster(String name, String description, Map<String, Object> props,
                                          List<String> required) {
        return tool(name, description, props, required, (ex, args) -> clusterCall(name, args));
    }

    private CallToolResult clusterCall(String name, Map<String, Object> args) {
        Instant to = parse(text(args, "to", null), clock.instant());
        Instant from = parse(text(args, "from", null), to.minus(DEFAULT_WINDOW));
        String note = "";
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            from = to.minus(MAX_WINDOW);
            note = "\n(window shortened to the last 60 minutes before 'to')";
        }
        RunWindow window = new RunWindow("mcp", properties.canonicalDrop(), from, to);
        var fact = tools.call(name, JSON.valueToTree(args), window, new Facts());
        return textResult(fact.value() + note);
    }

    private static Instant parse(String value, Instant otherwise) {
        try {
            return value == null ? otherwise : Instant.parse(value);
        } catch (RuntimeException e) {
            throw new ToolError("times are ISO-8601, e.g. 2026-09-26T03:00:00Z");
        }
    }

    private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(a);
        m.putAll(b);
        return m;
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
