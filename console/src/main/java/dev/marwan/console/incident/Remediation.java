package dev.marwan.console.incident;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.chaos.ClusterWrites;
import dev.marwan.console.cluster.KubernetesAccess;

/**
 * Applies a fix a person approved - the only path by which anything the agent
 * proposed changes the cluster.
 *
 * It runs inside the watcher's update, so an approval cannot race another one
 * or land on an incident that closed a moment earlier. Raising an autoscaler's
 * minimum is temporary: the previous minimum is recorded and put back after
 * ten minutes, so a drill does not leave the system permanently larger.
 */
public class Remediation {

    public enum Result { APPLIED, DISMISSED, NOT_FOUND, NOT_PENDING, FAILED }

    static final Duration TEMPORARY = Duration.ofMinutes(10);

    private final ClusterWrites writes;
    private final ChaosService chaos;
    private final IncidentWatcher watcher;
    private final RevertStore reverts;
    static final int MIN_REPLICAS = 2;
    static final int MAX_REPLICAS = 4;
    private final Clock clock;

    public Remediation(ClusterWrites writes, ChaosService chaos, IncidentWatcher watcher, RevertStore reverts,
                       Clock clock) {
        this.writes = writes;
        this.chaos = chaos;
        this.watcher = watcher;
        this.reverts = reverts;
        this.clock = clock;
    }

    public Result approve(String id, int n) {
        return decide(id, n, true);
    }

    public Result dismiss(String id, int n) {
        return decide(id, n, false);
    }

    private Result decide(String id, int n, boolean approve) {
        AtomicReference<Result> result = new AtomicReference<>(Result.NOT_PENDING);
        Optional<Incident> found = watcher.update(id, i -> {
            Instant now = clock.instant();
            int index = n - 1;
            if (!i.isOpen() || index < 0 || index >= i.proposals.size()
                    || !"pending".equals(i.proposals.get(index).status())) {
                return;
            }
            Incident.Proposal p = i.proposals.get(index);
            if (!approve) {
                i.proposals.set(index, p.decided("dismissed", now));
                i.add(now, "human", "dismissed by a key holder: " + IncidentCommander.describe(p));
                result.set(Result.DISMISSED);
                return;
            }
            i.add(now, "human", "approved by a key holder: " + IncidentCommander.describe(p));
            try {
                apply(p, i, now);
                i.proposals.set(index, p.decided("approved", now));
                i.status = "mitigating";
                i.add(now, "action", "applied: " + IncidentCommander.describe(p));
                result.set(Result.APPLIED);
            } catch (RuntimeException e) {
                i.proposals.set(index, p.decided("failed", now));
                i.add(now, "action", "could not apply " + IncidentCommander.describe(p) + ": "
                        + KubernetesAccess.summarise(e));
                result.set(Result.FAILED);
            }
        });
        return found.isEmpty() ? Result.NOT_FOUND : result.get();
    }

    private void apply(Incident.Proposal p, Incident i, Instant now) {
        switch (p.action()) {
            case "restart-booking" -> writes.restart("booking-service");
            case "scale-booking" -> {
                int n = replicas(p);
                reverts.raise("booking-service", writes.hpaMin("booking-service"), now.plus(TEMPORARY), i.id);
                writes.scale("booking-service", n);
                writes.setHpaMin("booking-service", n);
            }
            case "raise-hpa-min" -> {
                String hpa = "queue-gate".equals(p.target()) ? "queue-gate" : "booking-service";
                int n = replicas(p);
                reverts.raise(hpa, writes.hpaMin(hpa), now.plus(TEMPORARY), i.id);
                writes.setHpaMin(hpa, n);
            }
            case "end-fault" -> chaos.end();
            default -> throw new IllegalArgumentException("not on the menu: " + p.action());
        }
    }

    /** Replicas within the manifest's floor and the menu's ceiling, or the fix is refused. */
    private static int replicas(Incident.Proposal p) {
        int n = p.replicas() == null ? 3 : p.replicas();
        if (n < MIN_REPLICAS || n > MAX_REPLICAS) {
            throw new IllegalArgumentException("replicas must be between " + MIN_REPLICAS + " and " + MAX_REPLICAS
                    + ", not " + n);
        }
        return n;
    }

    /** Put back each temporary raise whose time is up, to the minimum it had before the first raise. */
    public void revertDue() {
        Instant now = clock.instant();
        for (RevertStore.Pending r : reverts.pending()) {
            if (r.until().isAfter(now)) {
                continue;
            }
            try {
                writes.setHpaMin(r.hpa(), r.original());
                reverts.remove(r.hpa());
                note(r.incident(), now, "reverted " + r.hpa() + "'s autoscaler minimum to " + r.original());
            } catch (RuntimeException e) {
                note(r.incident(), now, "could not revert " + r.hpa() + ": " + KubernetesAccess.summarise(e));
            }
        }
    }

    private void note(String incident, Instant now, String text) {
        if (incident != null && !incident.isBlank()) {
            watcher.update(incident, i -> i.add(now, "action", text));
        }
    }
}
