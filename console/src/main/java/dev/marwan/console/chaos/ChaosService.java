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
import org.springframework.web.client.RestClient;

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
 */
public class ChaosService {

    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);

    static final String NAME = "chaos-state";
    public static final Set<String> FAULTS = Set.of("kill-booking-pod", "slow-database", "squeeze-pool");
    static final int POD_SECONDS = 90;
    static final int APP_SECONDS = 120;

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

    private final AnalysisStore.ConfigMapPort maps;
    private final Supplier<List<Pod>> bookingPods;
    private final ClusterWrites writes;
    private final RestClient booking;
    private final Consumer<ActiveFault> onDrill;
    private final Clock clock;

    public ChaosService(AnalysisStore.ConfigMapPort maps, Supplier<List<Pod>> bookingPods, ClusterWrites writes,
                        RestClient booking, Consumer<ActiveFault> onDrill, Clock clock) {
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
        Optional<ActiveFault> active = existing.flatMap(this::read).filter(a -> a.until().isAfter(now));
        if (active.isPresent()) {
            throw new Busy(active.get());
        }
        ActiveFault next = new ActiveFault(fault, now,
                now.plusSeconds(fault.equals("kill-booking-pod") ? POD_SECONDS : APP_SECONDS));
        try {
            write(existing, next);
        } catch (AnalysisStore.Conflict e) {
            throw new Busy(current().orElse(null));
        }
        apply(fault);
        log.warn("chaos: injected {} until {}", fault, next.until());
        onDrill.accept(next);
        return next;
    }

    public Optional<ActiveFault> current() {
        Instant now = clock.instant();
        return maps.get(NAME).flatMap(this::read).filter(a -> a.until().isAfter(now));
    }

    /** Ends the active fault early. The lock is released by moving its expiry to now. */
    public void end() {
        Optional<ConfigMap> existing = maps.get(NAME);
        Optional<ActiveFault> active = existing.flatMap(this::read).filter(a -> a.until().isAfter(clock.instant()));
        if (active.isEmpty()) {
            return;
        }
        if (!active.get().fault().equals("kill-booking-pod")) {
            booking.delete().uri("/internal/chaos").retrieve().toBodilessEntity();
        }
        write(existing, new ActiveFault(active.get().fault(), active.get().startedAt(), clock.instant()));
    }

    private void apply(String fault) {
        if (fault.equals("kill-booking-pod")) {
            Pod victim = bookingPods.get().stream()
                    .filter(ChaosService::ready)
                    .max(Comparator.comparing(p -> p.getMetadata().getCreationTimestamp()))
                    .orElseThrow(() -> new IllegalStateException("no ready booking-service pod to kill"));
            writes.deletePod(victim.getMetadata().getName());
        } else {
            booking.post().uri("/internal/chaos").body(Map.of("fault", fault, "seconds", APP_SECONDS))
                    .retrieve().toBodilessEntity();
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
