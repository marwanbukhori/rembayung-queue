package dev.marwan.console.incident;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.marwan.console.agent.Analyst;
import dev.marwan.console.agent.Claim;
import dev.marwan.console.agent.Fact;
import dev.marwan.console.agent.Facts;
import dev.marwan.console.agent.Message;
import dev.marwan.console.agent.Model;
import dev.marwan.console.agent.Report;
import dev.marwan.console.agent.Validator;
import dev.marwan.console.objects.LogLines;
import tools.jackson.databind.JsonNode;

/**
 * The blameless postmortem, written once when an incident ends.
 *
 * Time to detect and time to recover are computed here from the timeline -
 * they are the numbers an SRE reader looks for first, so a model never
 * supplies them. The prose is the model's if it passes the validator against
 * the incident's facts, and built by rules otherwise.
 */
public class PostmortemWriter {

    static final String PROMPT = """
            Write a short blameless postmortem for this incident as one JSON object:
            {"summary": "...", "impact": "...", "root_cause": "...", "fix": "...", "follow_ups": ["...", "..."]}
            One or two sentences each, at most 3 follow-ups. Every number you write must appear in the facts; do not
            compute new numbers.
            """;

    private final Model model;
    private final Validator validator = new Validator();

    public PostmortemWriter(Model model) {
        this.model = model;
    }

    public Incident.Postmortem write(Incident incident) {
        long detect = incident.detectedAt == null ? -1
                : Duration.between(incident.openedAt, incident.detectedAt).toSeconds();
        long recover = incident.resolvedAt == null ? -1
                : Duration.between(incident.detectedAt != null ? incident.detectedAt : incident.openedAt,
                        incident.resolvedAt).toSeconds();
        Facts facts = facts(incident, detect, recover);
        try {
            String reply = model.chat(List.of(new Message("system", PROMPT),
                    new Message("user", "Facts:\n" + IncidentCommander.render(facts.all())
                            + "\nTimeline:\n" + timeline(incident))), IncidentCommander.PER_CALL);
            JsonNode n = Analyst.parse(reply);
            if (n != null) {
                List<String> followUps = new ArrayList<>();
                n.path("follow_ups").forEach(f -> followUps.add(LogLines.mask(Analyst.text(f))));
                List<String> texts = new ArrayList<>(List.of(Analyst.text(n.path("summary")), Analyst.text(n.path("impact")),
                        Analyst.text(n.path("root_cause")), Analyst.text(n.path("fix"))));
                texts.addAll(followUps);
                List<String> allIds = facts.all().stream().map(Fact::id).toList();
                List<Claim> claims = texts.stream().filter(t -> !t.isBlank()).map(t -> new Claim(t, allIds)).toList();
                if (!claims.isEmpty() && !Analyst.text(n.path("summary")).isBlank()
                        && validator.problems(Report.sections(claims, List.of(), List.of(), List.of(), List.of()), facts).isEmpty()) {
                    return new Incident.Postmortem(LogLines.mask(texts.get(0)), LogLines.mask(texts.get(1)),
                            LogLines.mask(texts.get(2)), LogLines.mask(texts.get(3)), followUps, detect, recover, "model");
                }
            }
        } catch (RuntimeException e) {
            // Fall through to the rules.
        }
        return fallback(incident, detect, recover);
    }

    private static Facts facts(Incident i, long detect, long recover) {
        Facts facts = new Facts();
        facts.add("incident", "Kind", i.kind + (i.fault == null ? "" : ", fault " + i.fault));
        facts.add("incident", "Outcome", i.status);
        facts.add("incident", "Time to detect", detect < 0 ? "not detected" : detect + " s");
        facts.add("incident", "Time to recover", recover < 0 ? "not recovered" : recover + " s");
        if (!i.diagnoses.isEmpty()) {
            facts.add("agent", "Last diagnosis", i.diagnoses.get(i.diagnoses.size() - 1).cause());
        }
        i.proposals.stream().filter(p -> "approved".equals(p.status())).forEach(p ->
                facts.add("human", "Approved fix", IncidentCommander.describe(p)));
        return facts;
    }

    private static String timeline(Incident i) {
        StringBuilder out = new StringBuilder();
        i.timeline.forEach(e -> out.append(e.at()).append(' ').append(e.source()).append(": ").append(e.text()).append('\n'));
        return out.toString();
    }

    static Incident.Postmortem fallback(Incident i, long detect, long recover) {
        String cause = i.diagnoses.stream().filter(d -> !d.cause().startsWith("could not"))
                .reduce((a, b) -> b).map(Incident.Diagnosis::cause).orElse("not established");
        List<String> fixes = i.proposals.stream().filter(p -> "approved".equals(p.status()))
                .map(IncidentCommander::describe).toList();
        String summary = ("drill".equals(i.kind) ? "A drill (" + i.fault + ")" : "An SLO breach")
                + " opened an incident that ended " + i.status + ".";
        String impact = "Booking SLOs were breached while the incident was open; see the timeline.";
        String fix = fixes.isEmpty() ? (i.fault != null ? "The fault ended by itself." : "The system recovered without an approved fix.")
                : "Approved: " + String.join("; ", fixes) + ".";
        List<String> followUps = new ArrayList<>();
        if (cause.contains("pool") || "squeeze-pool".equals(i.fault) || "slow-database".equals(i.fault)) {
            followUps.add("Scale booking-service on connection pressure, not CPU.");
        }
        if (detect < 0) {
            followUps.add("The agent did not name a cause; review its trail and the facts it had.");
        }
        return new Incident.Postmortem(summary, impact, cause, fix, followUps, detect, recover, "fallback");
    }
}
