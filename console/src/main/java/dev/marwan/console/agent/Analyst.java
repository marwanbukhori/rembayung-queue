package dev.marwan.console.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.marwan.console.objects.LogLines;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The agent: facts first, then at most five questions, then a report that has
 * to survive the validator.
 *
 * Bounded on every side, because the model is an 8B one on a shared server:
 * five tool calls, sixty seconds a call, three minutes a run, one retry. When
 * any bound is hit the run still ends in a report - the model's if it passed,
 * the facts' own if not - so a reader never finds a run with nothing to say.
 *
 * The model answers in a small JSON protocol written into the prompt rather
 * than through the server's native tool calling, which works whatever flags
 * the shared vLLM server was started with.
 */
public class Analyst {

    static final int MAX_CALLS = 5;
    static final Duration PER_CALL = Duration.ofSeconds(60);
    static final Duration PER_RUN = Duration.ofMinutes(3);
    static final String PROMPT_VERSION = "1";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SYSTEM = """
            You analyse one load test ("a rush") against a restaurant's virtual queue and booking service on OpenShift.
            queue-gate admits customers from a Redis queue; booking-service books seats in Oracle through a pool of 5
            database connections per pod; the invariant is that no seat is ever sold twice ("oversold" must be 0).
            A pool at its size (5 of 5) is saturated: requests queue for a connection and time out, which is a
            finding to explain, not efficiency. Rejections of 403 or 409 are the system working; 5xx and
            "not clean" bookings are not.

            You are given numbered facts (F1, F2, ...). You may investigate with up to %d tool calls. Each result becomes
            a new fact. Tools:
            %s
            Reply with exactly one JSON object and nothing else:
              {"call": "<tool>", "args": {...}, "why": "<one short reason>"}  to use a tool, or
              {"done": true}  when you have enough.
            Make each call answer a different question; do not repeat one tool across every replica.
            """.formatted(MAX_CALLS, Tools.MENU);

    static final String REPORT = """
            Now write the report. Reply with exactly one JSON object and nothing else:
            {"went_well": [{"text": "...", "facts": ["F1"]}], "caught": [...], "look_at": [...]}
            Rules: every item cites the fact ids it rests on; every number you write must appear in a cited fact;
            do not write clock times or dates;
            one or two sentences per item; at most 3 items per list; say what a reader should do in look_at.
            """;

    private final Function<RunWindow, Facts> baseline;
    private final Tools tools;
    private final Model model;
    private final Clock clock;
    private final Validator validator = new Validator();

    public Analyst(Function<RunWindow, Facts> baseline, Tools tools, Model model, Clock clock) {
        this.baseline = baseline;
        this.tools = tools;
        this.model = model;
        this.clock = clock;
    }

    public Analysis analyse(RunWindow w) {
        Instant began = clock.instant();
        return run(w, baseline.apply(w), began);
    }

    /** Re-run the model on facts already gathered: the baseline is kept, tool facts are dropped and re-earned. */
    public Analysis reanalyse(RunWindow w, List<Fact> stored) {
        Instant began = clock.instant();
        List<Fact> base = stored.stream().takeWhile(f -> !f.source().startsWith("tool:")).toList();
        return run(w, new Facts(base), began);
    }

    private Analysis run(RunWindow w, Facts facts, Instant began) {
        Instant deadline = began.plus(PER_RUN);
        List<TrailStep> trail = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", SYSTEM));
        messages.add(new Message("user", "Facts:\n" + render(facts.all()) + "\nCalls left: " + MAX_CALLS));
        List<String> problems = new ArrayList<>();
        try {
            for (int calls = 0; calls < MAX_CALLS && clock.instant().isBefore(deadline); calls++) {
                String reply = model.chat(messages, timeout(deadline));
                JsonNode step = parse(reply);
                if (step == null || !step.path("call").isString()) {
                    break;
                }
                Fact fact = tools.call(step.path("call").asString(), step.path("args"), w, facts);
                trail.add(new TrailStep(step.path("call").asString(), LogLines.mask(step.path("args").toString()),
                        LogLines.mask(text(step.path("why"))), fact.id()));
                messages.add(new Message("assistant", reply));
                messages.add(new Message("user", render(List.of(fact)) + "\nCalls left: " + (MAX_CALLS - calls - 1)));
            }
            messages.add(new Message("user", REPORT));
            for (int attempt = 0; attempt < 2; attempt++) {
                if (!clock.instant().isBefore(deadline)) {
                    return fallback(w, facts, trail, "the three-minute budget ran out", problems, began);
                }
                String reply = model.chat(messages, timeout(deadline));
                Report report = report(parse(reply));
                problems = report == null ? List.of("the answer was not the report JSON") : validator.problems(report, facts);
                if (problems.isEmpty()) {
                    return new Analysis(w.job(), w.dropId(), w.start(), w.end(), facts.all(), trail, report,
                            model.name(), "model", null, List.of(), clock.instant(), millis(began));
                }
                messages.add(new Message("assistant", reply));
                messages.add(new Message("user", "Fix these and answer with the corrected JSON only:\n- "
                        + String.join("\n- ", problems)));
            }
            return fallback(w, facts, trail, "the model's report failed validation twice", problems, began);
        } catch (ModelUnavailable e) {
            return fallback(w, facts, trail, e.getMessage(), problems, began);
        } catch (RuntimeException e) {
            // Whatever nobody planned for still ends in a report, rather than a run retried every tick.
            return fallback(w, facts, trail, "the analysis failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()), problems, began);
        }
    }

    private Analysis fallback(RunWindow w, Facts facts, List<TrailStep> trail, String why, List<String> problems,
                              Instant began) {
        return new Analysis(w.job(), w.dropId(), w.start(), w.end(), facts.all(), trail, Fallback.from(facts),
                model.name(), "fallback", why, List.copyOf(problems), clock.instant(), millis(began));
    }

    private Duration timeout(Instant deadline) {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.compareTo(PER_CALL) < 0 ? (left.isNegative() ? Duration.ofSeconds(1) : left) : PER_CALL;
    }

    private long millis(Instant began) {
        return Math.max(0, Duration.between(began, clock.instant()).toMillis());
    }

    static String render(List<Fact> facts) {
        return facts.stream().map(f -> f.id() + " [" + f.source() + "] " + f.label() + ": " + f.value())
                .collect(Collectors.joining("\n"));
    }

    /** The first JSON object in the reply, tolerating a markdown fence or a sentence around it. */
    static JsonNode parse(String reply) {
        if (reply == null) {
            return null;
        }
        int open = reply.indexOf('{');
        int close = reply.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(reply.substring(open, close + 1));
            return node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** A node's text, or its JSON when the model put an object where a string belongs. */
    static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.isString() ? node.asString() : node.toString();
    }

    static Report report(JsonNode node) {
        if (node == null || !(node.has("went_well") || node.has("caught") || node.has("look_at"))) {
            return null;
        }
        return new Report(claims(node.path("went_well")), claims(node.path("caught")), claims(node.path("look_at")));
    }

    private static List<Claim> claims(JsonNode list) {
        List<Claim> out = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode item : list) {
                List<String> ids = new ArrayList<>();
                item.path("facts").forEach(id -> ids.add(text(id)));
                String text = text(item.path("text"));
                // An empty placeholder ({} or text "") claims nothing, so it is dropped rather than failed.
                if (!text.isBlank() || !ids.isEmpty()) {
                    out.add(new Claim(text, ids));
                }
            }
        }
        return out;
    }
}
