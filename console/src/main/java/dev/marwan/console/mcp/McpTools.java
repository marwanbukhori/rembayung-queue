package dev.marwan.console.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.incident.Incident;
import dev.marwan.console.incident.IncidentCommander;
import dev.marwan.console.incident.IncidentStore;
import dev.marwan.console.incident.IncidentWatcher;
import dev.marwan.console.objects.LogLine;
import dev.marwan.console.objects.LogLines;
import dev.marwan.console.objects.LogPage;
import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.objects.ObjectsProvider;
import dev.marwan.console.objects.PodLogs;
import dev.marwan.console.ops.DropOps;
import dev.marwan.console.ops.LoadOps;
import dev.marwan.console.ops.LoadRun;
import dev.marwan.console.slo.SloReading;
import dev.marwan.console.slo.SloService;
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
    private final SloService slo;
    private final IncidentStore incidents;
    private final IncidentWatcher watcher;
    private final ChaosService chaos;
    private final Clock clock;

    public McpTools(DemoStateProvider demo, ClusterStateProvider cluster, ConsoleProperties properties,
                    AccessKey key, AnalysisStore store, ObjectsProvider objects, PodLogs podLogs, Tools tools,
                    ObjectSource source, DropOps dropOps, LoadOps loadOps, SloService slo, IncidentStore incidents,
                    IncidentWatcher watcher, ChaosService chaos, Clock clock) {
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
        this.slo = slo;
        this.incidents = incidents;
        this.watcher = watcher;
        this.chaos = chaos;
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
                    if (keyed(ex) || agent(ex)) {
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
        tools.addAll(incidentTools());
        return tools;
    }

    /**
     * SLOs, incidents, drills and proposals. There is deliberately no tool that
     * approves, dismisses or applies a proposal: that is a keyed request from a
     * person in the console, so no MCP client - the incident commander included -
     * can act on its own advice.
     */
    private List<SyncToolSpecification> incidentTools() {
        List<SyncToolSpecification> list = new ArrayList<>();
        list.add(tool("get_slo",
                "The two booking SLOs now and over the last 15 minutes: success ratio (target 0.99) and p95 latency "
                        + "(target 2 s) on booking-service /bookings over 5-minute windows, with the error-budget burn rate.",
                Map.of(), List.of(), (ex, args) -> {
                    SloReading now = slo.now();
                    List<Map<String, Object>> history = slo.history(DEFAULT_WINDOW).stream().map(McpTools::slo).toList();
                    return json(Map.of("now", slo(now), "history", history,
                            "targets", Map.of("successRatio", 0.99, "p95Seconds", 2.0)));
                }));
        list.add(tool("list_incidents",
                "Recent incidents, newest first: id, drill or breach, the fault, status, when opened and resolved.",
                Map.of(), List.of(), (ex, args) -> json(incidents.list().stream().map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", i.id);
                    m.put("kind", i.kind);
                    m.put("fault", i.fault);
                    m.put("status", i.status);
                    m.put("openedAt", i.openedAt);
                    m.put("resolvedAt", i.resolvedAt);
                    m.put("proposals", i.proposals.size());
                    return m;
                }).toList())));
        list.add(tool("get_incident",
                "One incident in full: timeline, diagnoses with their facts, proposals and their decisions, postmortem.",
                Map.of("id", str("The incident id from list_incidents")), List.of("id"),
                (ex, args) -> incidents.get(text(args, "id", ""))
                        .map(McpTools::json)
                        .orElseThrow(() -> new ToolError("no incident '" + text(args, "id", "") + "'; list_incidents shows them"))));
        list.add(tool("inject_fault",
                "Start a chaos drill: kill-booking-pod, slow-database or squeeze-pool. Each ends by itself within 2 "
                        + "minutes and opens a drill incident. One fault at a time. Needs the console key.",
                Map.of("fault", str("kill-booking-pod, slow-database or squeeze-pool")), List.of("fault"),
                (ex, args) -> {
                    if (!keyed(ex)) {
                        throw new ToolError(NEEDS_KEY);
                    }
                    try {
                        ChaosService.ActiveFault started = chaos.inject(text(args, "fault", ""));
                        return json(Map.of("fault", started.fault(), "until", started.until(),
                                "then", "list_incidents shows the drill incident; get_slo shows its effect"));
                    } catch (ChaosService.Busy e) {
                        throw new ToolError(e.getMessage() + "; one fault at a time, and none within 120 s of the last");
                    } catch (ChaosService.Refused | ChaosService.ApplyFailed e) {
                        throw new ToolError(e.getMessage());
                    } catch (IllegalArgumentException e) {
                        throw new ToolError("unknown fault; the faults are " + String.join(", ", ChaosService.FAULTS));
                    }
                }));
        list.add(tool("propose_remediation",
                "File a proposed fix on an open incident, from a fixed menu: restart-booking, scale-booking (replicas "
                        + "1-4), raise-hpa-min (target booking-service or queue-gate, replicas 1-4), end-fault. It is only "
                        + "a proposal: a person approves or dismisses it in the console. One pending proposal at a time. "
                        + "Needs the console key.",
                Map.of("incident", str("Incident id; default the open incident"),
                        "action", str("restart-booking, scale-booking, raise-hpa-min or end-fault"),
                        "target", str("booking-service (default) or queue-gate, for raise-hpa-min"),
                        "replicas", integer("2 to 4, for scale-booking and raise-hpa-min"),
                        "reason", str("Why, in one sentence")),
                List.of("action", "reason"),
                (ex, args) -> {
                    if (!keyed(ex)) {
                        throw new ToolError(NEEDS_KEY);
                    }
                    String action = text(args, "action", "");
                    if (!IncidentCommander.ACTIONS.contains(action)) {
                        throw new ToolError("not on the menu; the actions are " + String.join(", ", IncidentCommander.ACTIONS));
                    }
                    String id = text(args, "incident", null);
                    Optional<Incident> target = id == null ? incidents.open() : incidents.get(id);
                    if (target.isEmpty()) {
                        throw new ToolError(id == null ? "no incident is open" : "no incident '" + id + "'");
                    }
                    Integer n = replicasOf(text(args, "replicas", null));
                    AtomicReference<Object> filed = new AtomicReference<>("the incident is no longer open");
                    watcher.update(target.get().id, i -> {
                        if (!i.isOpen()) {
                            return;
                        }
                        if (i.proposals.stream().anyMatch(p -> "pending".equals(p.status()))) {
                            filed.set("a proposal is already pending; a person must approve or dismiss it first");
                            return;
                        }
                        Instant now = clock.instant();
                        Incident.Proposal p = new Incident.Proposal(i.proposals.size() + 1, now, action,
                                "queue-gate".equals(text(args, "target", "")) ? "queue-gate" : "booking-service",
                                n, LogLines.mask(text(args, "reason", "")), List.of(),
                                "pending", null);
                        i.proposals.add(p);
                        i.add(now, "agent", "proposes " + IncidentCommander.describe(p)
                                + " (over MCP) - awaiting a person's approval");
                        filed.set(p);
                    });
                    if (!(filed.get() instanceof Incident.Proposal p)) {
                        throw new ToolError(String.valueOf(filed.get()));
                    }
                    return json(Map.of("incident", target.get().id, "proposal", p.n(), "status", "pending",
                            "then", "a person approves or dismisses it on the console's Incidents page; "
                                    + "no MCP tool can approve"));
                }));
        return list;
    }

    private static Map<String, Object> slo(SloReading r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", r.at());
        m.put("available", r.available());
        m.put("hasTraffic", r.hasTraffic());
        m.put("successRatio", r.successRatio());
        m.put("p95Seconds", r.p95Seconds());
        m.put("state", !r.available() ? "unavailable" : !r.hasTraffic() ? "no traffic" : r.breached() ? "breached" : "ok");
        if (r.successRatio() != null) {
            m.put("burnRate", Math.round((1 - r.successRatio()) / (1 - 0.99) * 10) / 10.0);
        }
        return m;
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

    /** A proposal's replica count: absent, or a whole number from 2 to 4 - anything else is refused. */
    static Integer replicasOf(String value) {
        if (value == null) {
            return null;
        }
        try {
            int n = new java.math.BigDecimal(value).intValueExact();
            if (n >= 2 && n <= 4) {
                return n;
            }
        } catch (NumberFormatException | ArithmeticException e) {
            // Falls through to the refusal.
        }
        throw new ToolError("replicas must be a whole number from 2 to 4, not '" + value + "'");
    }

    /** The console's own agents: raw logs, and nothing that needs the key. */
    static boolean agent(McpSyncServerExchange exchange) {
        return Boolean.TRUE.equals(exchange.transportContext().get(McpConfiguration.AGENT));
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
