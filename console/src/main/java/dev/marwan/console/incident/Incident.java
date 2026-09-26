package dev.marwan.console.incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import dev.marwan.console.agent.Claim;
import dev.marwan.console.agent.Fact;

/**
 * One incident, as stored: what opened it, everything that happened, what the
 * agent concluded and proposed, what a person approved, and how it ended.
 *
 * Mutable on purpose - it is built up tick by tick and written back whole - and
 * plain fields so it round-trips through JSON without ceremony.
 */
public class Incident {

    /** A diagnosis: the cause the agent names, how sure it is, and the claims it rests on. */
    public record Diagnosis(Instant at, String cause, String confidence, List<Claim> claims) { }

    /** A proposed fix from the fixed menu; only a person can move it past pending. */
    public record Proposal(int n, Instant at, String action, String target, Integer replicas, String reason,
                           List<String> facts, String status, Instant decidedAt) {

        public Proposal decided(String newStatus, Instant when) {
            return new Proposal(n, at, action, target, replicas, reason, facts, newStatus, when);
        }
    }

    /** A temporary raise to undo later. */
    public record Revert(String hpa, int minReplicas, Instant at) { }

    public record Postmortem(String summary, String impact, String rootCause, String fix, List<String> followUps,
                             long timeToDetectSeconds, long timeToRecoverSeconds, String source) { }

    public String id;
    /** "drill" when a fault opened it, "breach" when the SLOs did. */
    public String kind;
    public String fault;
    public Instant openedAt;
    /** When the agent first named a cause. */
    public Instant detectedAt;
    public Instant resolvedAt;
    /** open, mitigating, resolved or unresolved. */
    public String status;
    public List<IncidentEvent> timeline = new ArrayList<>();
    public List<Fact> facts = new ArrayList<>();
    public List<Diagnosis> diagnoses = new ArrayList<>();
    public List<Proposal> proposals = new ArrayList<>();
    public List<Revert> reverts = new ArrayList<>();
    public Postmortem postmortem;
    public int cycles;

    // The watcher's own bookkeeping, kept here so a restart carries on where it stopped.
    public Boolean lastBreached;
    public Instant healthySince;
    public List<String> knownPods = new ArrayList<>();
    public List<String> seenWarnings = new ArrayList<>();

    public boolean isOpen() {
        return "open".equals(status) || "mitigating".equals(status);
    }

    public void add(Instant at, String source, String text) {
        timeline.add(new IncidentEvent(at, source, text));
    }
}
