package dev.marwan.console.incident;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marwan.console.agent.Analyst;
import dev.marwan.console.agent.Claim;
import dev.marwan.console.agent.Fact;
import dev.marwan.console.agent.Facts;
import dev.marwan.console.agent.Message;
import dev.marwan.console.agent.Model;
import dev.marwan.console.agent.ModelUnavailable;
import dev.marwan.console.agent.Report;
import dev.marwan.console.agent.RunWindow;
import dev.marwan.console.agent.ToolCaller;
import dev.marwan.console.agent.Tools;
import dev.marwan.console.agent.Validator;
import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.objects.LogLines;
import dev.marwan.console.slo.SloReading;
import tools.jackson.databind.JsonNode;

/**
 * The agent in an incident: every cycle it looks - up to three read-only
 * questions through MCP - then names a cause, how sure it is, and at most one
 * fix from a fixed menu.
 *
 * It proposes and nothing more. A proposal is data in the incident, pending
 * until a person with the console key approves it in the console; nothing here
 * applies anything, and no tool it can call does either. Its claims pass the
 * same validator as every report, so a number it states is a number the facts
 * contain.
 */
public class IncidentCommander {

    private static final Logger log = LoggerFactory.getLogger(IncidentCommander.class);
    static final int MAX_CYCLES = 10;
    static final int CALLS_PER_CYCLE = 3;
    static final Duration PER_CALL = Duration.ofSeconds(60);
    public static final Set<String> ACTIONS = Set.of("restart-booking", "scale-booking", "raise-hpa-min", "end-fault");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Kuala_Lumpur"));

    static final String SYSTEM = """
            You are the incident commander for a restaurant's virtual queue (queue-gate) and booking service
            (booking-service, a pool of 5 Oracle connections per pod) on OpenShift. An incident is open: an SLO
            breach, or a deliberate drill. Find the cause from the numbered facts and, if you can, propose ONE fix.
            You may ask up to %d questions with these read-only tools (each answer becomes a new fact):
            %s
            get_slo {} - both booking SLOs now and over the last 15 minutes
            Reply with exactly one JSON object: {"call": "<tool>", "args": {...}, "why": "..."} or {"done": true}.
            """.formatted(CALLS_PER_CYCLE, Tools.MENU);

    static final String DIAGNOSE = """
            Now answer with exactly one JSON object:
            {"cause": "...", "confidence": "low"|"medium"|"high",
             "claims": [{"text": "...", "facts": ["F1"]}],
             "proposal": {"action": "restart-booking"|"scale-booking"|"raise-hpa-min"|"end-fault",
                          "target": "booking-service"|"queue-gate", "replicas": 1-4, "reason": "...", "facts": ["F1"]} or null}
            Every number in a claim must appear in a cited fact; do not compute new numbers. Propose only with medium or
            high confidence. A person approves or dismisses the proposal; you cannot apply it.
            """;

    private final ToolCaller tools;
    private final Model model;
    private final Supplier<SloReading> slo;
    private final Supplier<Optional<ChaosService.ActiveFault>> fault;
    private final IncidentStore store;
    private final IncidentWatcher watcher;
    private final Clock clock;
    private final Validator validator = new Validator();

    public IncidentCommander(ToolCaller tools, Model model, Supplier<SloReading> slo,
                             Supplier<Optional<ChaosService.ActiveFault>> fault, IncidentStore store,
                             IncidentWatcher watcher, Clock clock) {
        this.tools = tools;
        this.model = model;
        this.slo = slo;
        this.fault = fault;
        this.store = store;
        this.watcher = watcher;
        this.clock = clock;
    }

    /** One cycle on the open incident, if there is one. */
    public void cycle() {
        Optional<Incident> open = store.open();
        if (open.isEmpty()) {
            return;
        }
        Incident snapshot = open.get();
        Instant now = clock.instant();
        if (snapshot.cycles >= MAX_CYCLES) {
            watcher.update(snapshot.id, i -> {
                if (i.isOpen()) {
                    i.status = "unresolved";
                    i.resolvedAt = now;
                    i.add(now, "agent", "gave up after " + MAX_CYCLES + " cycles; over to a person");
                }
            });
            return;
        }
        Facts facts = baseline(snapshot);
        Outcome outcome = investigate(facts, now);
        watcher.update(snapshot.id, i -> {
            i.cycles++;
            if (!i.isOpen()) {
                return;
            }
            Incident.Diagnosis d = outcome.diagnosis();
            i.diagnoses.add(d);
            i.add(now, "agent", "diagnosis (" + d.confidence() + "): " + d.cause());
            if (outcome.named() && i.detectedAt == null) {
                i.detectedAt = now;
            }
            JsonNode p = outcome.proposal();
            boolean pending = i.proposals.stream().anyMatch(x -> "pending".equals(x.status()));
            if (p != null && outcome.named() && !pending && !"low".equals(d.confidence())
                    && ACTIONS.contains(Analyst.text(p.path("action")))) {
                Incident.Proposal proposal = new Incident.Proposal(i.proposals.size() + 1, now,
                        Analyst.text(p.path("action")), target(p), replicas(p),
                        LogLines.mask(Analyst.text(p.path("reason"))), ids(p.path("facts")), "pending", null);
                i.proposals.add(proposal);
                i.add(now, "agent", "proposes " + describe(proposal) + " - awaiting a person's approval");
            }
        });
    }

    record Outcome(Incident.Diagnosis diagnosis, JsonNode proposal, boolean named) { }

    private Outcome investigate(Facts facts, Instant now) {
        RunWindow window = new RunWindow("incident", "", now.minus(Duration.ofMinutes(15)), now);
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", SYSTEM));
        messages.add(new Message("user", "Facts:\n" + render(facts)));
        try {
            for (int calls = 0; calls < CALLS_PER_CYCLE; calls++) {
                String reply = model.chat(messages, PER_CALL);
                JsonNode step = Analyst.parse(reply);
                if (step == null || !step.path("call").isString()) {
                    break;
                }
                Fact fact = tools.call(step.path("call").asString(), step.path("args"), window, facts).fact();
                messages.add(new Message("assistant", reply));
                messages.add(new Message("user", render(List.of(fact))));
            }
            messages.add(new Message("user", DIAGNOSE));
            for (int attempt = 0; attempt < 2; attempt++) {
                String reply = model.chat(messages, PER_CALL);
                JsonNode answer = Analyst.parse(reply);
                List<String> problems = check(answer, facts);
                if (problems.isEmpty()) {
                    return new Outcome(new Incident.Diagnosis(now, LogLines.mask(Analyst.text(answer.path("cause"))),
                            confidence(answer), claims(answer.path("claims")), facts.all()),
                            answer.path("proposal").isObject() ? answer.path("proposal") : null, true);
                }
                messages.add(new Message("assistant", reply));
                messages.add(new Message("user", "Fix these and answer with the corrected JSON only:\n- "
                        + String.join("\n- ", problems)));
            }
        } catch (ModelUnavailable e) {
            log.warn("incident commander: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("incident commander failed a cycle: {}", e.toString());
        }
        Fact first = facts.all().get(0);
        return new Outcome(new Incident.Diagnosis(now, "could not determine the cause yet", "low",
                List.of(new Claim(first.label() + ": " + first.value() + ".", List.of(first.id()))), facts.all()),
                null, false);
    }

    private List<String> check(JsonNode answer, Facts facts) {
        if (answer == null || Analyst.text(answer.path("cause")).isBlank()) {
            return List.of("the answer needs a cause");
        }
        List<Claim> claims = claims(answer.path("claims"));
        if (claims.isEmpty()) {
            return List.of("the answer needs at least one claim citing facts");
        }
        return validator.problems(Report.sections(claims, List.of(), List.of(), List.of(), List.of()), facts);
    }

    /** What the commander knows before it asks anything: the SLOs, the fault, the recent timeline. */
    Facts baseline(Incident incident) {
        Facts facts = new Facts();
        SloReading r = slo.get();
        facts.add("slo", "Booking success (5 m)", r.successRatio() == null ? "no traffic"
                : Math.round(r.successRatio() * 1000) / 10.0 + "%");
        facts.add("slo", "Booking p95 (5 m)", r.p95Seconds() == null ? "no traffic" : r.p95Seconds() + " s");
        facts.add("slo", "SLO state", !r.available() ? "unavailable: " + r.detail() : r.breached() ? "breached" : "within target");
        facts.add("chaos", "Active fault", fault.get().map(f -> f.fault() + ", ends by itself").orElse("none"));
        List<IncidentEvent> recent = incident.timeline.subList(Math.max(0, incident.timeline.size() - 6),
                incident.timeline.size());
        for (IncidentEvent e : recent) {
            facts.add("timeline", "Timeline " + e.source(), HMS.format(e.at()) + " " + e.text());
        }
        return facts;
    }

    public static String describe(Incident.Proposal p) {
        return switch (p.action()) {
            case "scale-booking" -> "scaling booking-service to " + p.replicas();
            case "raise-hpa-min" -> "raising " + p.target() + "'s autoscaler minimum to " + p.replicas();
            case "restart-booking" -> "restarting booking-service";
            case "end-fault" -> "ending the fault now";
            default -> p.action();
        };
    }

    private static String target(JsonNode p) {
        String t = Analyst.text(p.path("target"));
        return "queue-gate".equals(t) ? "queue-gate" : "booking-service";
    }

    private static Integer replicas(JsonNode p) {
        int n = p.path("replicas").asInt(0);
        return n <= 0 ? null : Math.min(n, 4);
    }

    private static String confidence(JsonNode answer) {
        String c = Analyst.text(answer.path("confidence"));
        return Set.of("low", "medium", "high").contains(c) ? c : "low";
    }

    private static List<Claim> claims(JsonNode list) {
        List<Claim> out = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                String text = Analyst.text(item.path("text"));
                List<String> ids = ids(item.path("facts"));
                if (!text.isBlank() || !ids.isEmpty()) {
                    out.add(new Claim(LogLines.mask(text), ids));
                }
            }
        }
        return out;
    }

    private static List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        if (list != null && list.isArray()) {
            list.forEach(id -> out.add(Analyst.text(id)));
        }
        return out;
    }

    static String render(List<Fact> facts) {
        StringBuilder out = new StringBuilder();
        for (Fact f : facts) {
            out.append(f.id()).append(" [").append(f.source()).append("] ").append(f.label()).append(": ")
                    .append(f.value()).append('\n');
        }
        return out.toString();
    }

    private static String render(Facts facts) {
        return render(facts.all());
    }
}
