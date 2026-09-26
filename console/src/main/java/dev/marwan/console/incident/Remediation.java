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
    private final IncidentStore store;
    private final Clock clock;

    public Remediation(ClusterWrites writes, ChaosService chaos, IncidentWatcher watcher, IncidentStore store,
                       Clock clock) {
        this.writes = writes;
        this.chaos = chaos;
        this.watcher = watcher;
        this.store = store;
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
                int n = p.replicas() == null ? 3 : p.replicas();
                int previous = writes.hpaMin("booking-service");
                writes.scale("booking-service", n);
                writes.setHpaMin("booking-service", n);
                i.reverts.add(new Incident.Revert("booking-service", previous, now.plus(TEMPORARY)));
            }
            case "raise-hpa-min" -> {
                String hpa = "queue-gate".equals(p.target()) ? "queue-gate" : "booking-service";
                int previous = writes.hpaMin(hpa);
                writes.setHpaMin(hpa, p.replicas() == null ? 3 : p.replicas());
                i.reverts.add(new Incident.Revert(hpa, previous, now.plus(TEMPORARY)));
            }
            case "end-fault" -> chaos.end();
            default -> throw new IllegalArgumentException("not on the menu: " + p.action());
        }
    }

    /** Undo temporary raises whose ten minutes are up, on any incident, open or not. */
    public void revertDue() {
        Instant now = clock.instant();
        for (Incident incident : store.list()) {
            if (incident.reverts.stream().anyMatch(r -> !r.at().isAfter(now))) {
                watcher.update(incident.id, i -> {
                    List<Incident.Revert> keep = new ArrayList<>();
                    for (Incident.Revert r : i.reverts) {
                        if (r.at().isAfter(now)) {
                            keep.add(r);
                            continue;
                        }
                        try {
                            writes.setHpaMin(r.hpa(), r.minReplicas());
                            i.add(now, "action", "reverted " + r.hpa() + "'s autoscaler minimum to " + r.minReplicas());
                        } catch (RuntimeException e) {
                            keep.add(r);
                            i.add(now, "action", "could not revert " + r.hpa() + ": " + KubernetesAccess.summarise(e));
                        }
                    }
                    i.reverts = keep;
                });
            }
        }
    }
}
