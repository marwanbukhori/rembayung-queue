package dev.marwan.console.chaos;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Pod;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marwan.console.agent.AnalysisStore;

/**
 * Breaks the system on purpose, one fault at a time.
 *
 * The lock is a ConfigMap, not a field, so a console restart mid-drill neither
 * forgets the fault nor lets a second one start. It is written with the
 * resourceVersion it read, so two visitors pressing at the same instant get one
 * drill and one "busy" - the API's 409 is the tiebreak.
 *
 * Every fault ends without the console: a deleted pod is replaced by its
 * ReplicaSet, and booking-service reverts its own in-app faults at their time.
 * The lock's expiry only mirrors that.
 *
 * The key that starts a drill is public, so the lock also holds a cooldown
 * measured from when a fault started: ending a fault early does not let the
 * next one begin sooner. Without it, kill-end-kill would take down each
 * booking pod in turn, faster than the ReplicaSet can replace them.
 */
public class ChaosService {

    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);

    static final String NAME = "chaos-state";
    public static final Set<String> FAULTS = Set.of("kill-booking-pod", "slow-database", "squeeze-pool");
    static final int POD_SECONDS = 90;
    static final int APP_SECONDS = 120;
    /** No new fault of any kind until this long after the last one started. */
    static final int COOLDOWN_SECONDS = 120;
    static final int MIN_READY_TO_KILL = 2;

    public record ActiveFault(String fault, Instant startedAt, Instant until) { }

    /** Another fault is running; the message names it. */
    public static class Busy extends RuntimeException {
        private final transient ActiveFault active;

        public Busy(ActiveFault active) {
            super(active == null ? "another fault is being started" : active.fault() + " is running until " + active.until());
            this.active = active;
        }

        public ActiveFault active() {
            return active;
        }
    }

    /** The fault cannot be started safely right now; the message says why. */
    public static class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    /** The lock was taken but the fault could not be applied; the lock has been released. */
    public static class ApplyFailed extends RuntimeException {
        public ApplyFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final AnalysisStore.ConfigMapPort maps;
    private final Supplier<List<Pod>> bookingPods;
    private final ClusterWrites writes;
    private final BookingChaos booking;
    private final Consumer<ActiveFault> onDrill;
    private final Clock clock;

    public ChaosService(AnalysisStore.ConfigMapPort maps, Supplier<List<Pod>> bookingPods, ClusterWrites writes,
                        BookingChaos booking, Consumer<ActiveFault> onDrill, Clock clock) {
        this.maps = maps;
        this.bookingPods = bookingPods;
        this.writes = writes;
        this.booking = booking;
        this.onDrill = onDrill;
        this.clock = clock;
    }

    public ActiveFault inject(String fault) {
        if (!FAULTS.contains(fault)) {
            throw new IllegalArgumentException("unknown fault: " + fault + "; one of " + FAULTS);
        }
        Instant now = clock.instant();
        Optional<ConfigMap> existing = maps.get(NAME);
        Optional<ActiveFault> last = existing.flatMap(this::read);
        if (last.isPresent() && (last.get().until().isAfter(now)
                || last.get().startedAt().plusSeconds(COOLDOWN_SECONDS).isAfter(now))) {
            throw new Busy(last.get());
        }
        if (fault.equals("kill-booking-pod") && bookingPods.get().stream().filter(ChaosService::ready).count() < MIN_READY_TO_KILL) {
            throw new Refused("a pod is only killed while at least " + MIN_READY_TO_KILL
                    + " ready booking-service pods are serving");
        }
        ActiveFault next = new ActiveFault(fault, now,
                now.plusSeconds(fault.equals("kill-booking-pod") ? POD_SECONDS : APP_SECONDS));
        try {
            write(existing, next);
        } catch (AnalysisStore.Conflict e) {
            throw new Busy(current().orElse(null));
        }
        try {
            apply(fault);
        } catch (RuntimeException e) {
            // Nothing was broken, so nothing is held: release the lock and its cooldown.
            release(next);
            throw new ApplyFailed("could not start " + fault + ": " + e.getMessage(), e);
        }
        log.warn("chaos: injected {} until {}", fault, next.until());
        onDrill.accept(next);
        return next;
    }

    public Optional<ActiveFault> current() {
        Instant now = clock.instant();
        return maps.get(NAME).flatMap(this::read).filter(a -> a.until().isAfter(now));
    }

    /**
     * Ends an in-app fault early, on every booking pod. The fault stops now; the
     * cooldown still runs from its start. A killed pod cannot be un-killed, so
     * for that fault this does nothing and the lock stands.
     */
    public void end() {
        Optional<ConfigMap> existing = maps.get(NAME);
        Optional<ActiveFault> active = existing.flatMap(this::read).filter(a -> a.until().isAfter(clock.instant()));
        if (active.isEmpty() || active.get().fault().equals("kill-booking-pod")) {
            return;
        }
        for (String ip : podIps()) {
            try {
                booking.stop(ip);
            } catch (RuntimeException e) {
                log.warn("chaos: could not end {} on {}: {}", active.get().fault(), ip, e.getMessage());
            }
        }
        write(existing, new ActiveFault(active.get().fault(), active.get().startedAt(), clock.instant()));
    }

    private void release(ActiveFault taken) {
        try {
            Optional<ConfigMap> existing = maps.get(NAME);
            Instant now = clock.instant();
            write(existing, new ActiveFault(taken.fault(), now.minusSeconds(COOLDOWN_SECONDS), now));
        } catch (RuntimeException e) {
            log.warn("chaos: could not release the lock after a failed start: {}", e.getMessage());
        }
    }

    private List<String> podIps() {
        return bookingPods.get().stream()
                .map(p -> p.getStatus() == null ? null : p.getStatus().getPodIP())
                .filter(ip -> ip != null && !ip.isBlank()).toList();
    }

    private void apply(String fault) {
        if (fault.equals("kill-booking-pod")) {
            Pod victim = bookingPods.get().stream()
                    .filter(ChaosService::ready)
                    .max(Comparator.comparing(p -> p.getMetadata().getCreationTimestamp()))
                    .orElseThrow(() -> new IllegalStateException("no ready booking-service pod to kill"));
            writes.deletePod(victim.getMetadata().getName());
        } else {
            List<String> ips = podIps();
            if (ips.isEmpty()) {
                throw new IllegalStateException("no booking-service pod to reach");
            }
            for (String ip : ips) {
                booking.start(ip, fault, APP_SECONDS);
            }
        }
    }

    private static boolean ready(Pod p) {
        return p.getStatus() != null && p.getStatus().getContainerStatuses() != null
                && !p.getStatus().getContainerStatuses().isEmpty()
                && p.getStatus().getContainerStatuses().stream().allMatch(c -> Boolean.TRUE.equals(c.getReady()));
    }

    /** A clean object carrying the version it replaces; never the object read back. */
    private void write(Optional<ConfigMap> existing, ActiveFault f) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("fault", f.fault());
        data.put("startedAt", f.startedAt().toString());
        data.put("until", f.until().toString());
        ConfigMap map = new ConfigMapBuilder().withNewMetadata().withName(NAME)
                .addToLabels("app.kubernetes.io/component", "chaos")
                .withResourceVersion(existing.map(c -> c.getMetadata().getResourceVersion()).orElse(null))
                .endMetadata().withData(data).build();
        if (existing.isEmpty()) {
            maps.create(map);
        } else {
            maps.update(map);
        }
    }

    private Optional<ActiveFault> read(ConfigMap map) {
        try {
            Map<String, String> d = map.getData();
            return Optional.of(new ActiveFault(d.get("fault"), Instant.parse(d.get("startedAt")), Instant.parse(d.get("until"))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
