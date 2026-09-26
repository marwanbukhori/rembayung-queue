package dev.marwan.console.incident;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.slo.SloReading;

/**
 * Opens incidents, keeps their timeline, and closes them.
 *
 * A drill opens one at once, so the timeline starts at the cause. Otherwise an
 * SLO breach has to last 30 seconds - one bad scrape is not an incident. An
 * incident resolves when both SLOs have held for 60 seconds, or, on an idle
 * system, when its fault has ended and a minute has passed: a drill with no
 * traffic must end rather than wait forever for a signal that will not come.
 *
 * Everything it needs to carry on is in the stored incident, so a console that
 * restarts mid-incident picks up where it stopped.
 */
public class IncidentWatcher {

    private static final Logger log = LoggerFactory.getLogger(IncidentWatcher.class);
    static final Duration SUSTAINED = Duration.ofSeconds(30);
    static final Duration HOLD = Duration.ofSeconds(60);

    private final Supplier<SloReading> slo;
    private final IncidentStore store;
    private final Supplier<Optional<ChaosService.ActiveFault>> fault;
    private final Supplier<List<String>> pods;
    private final Supplier<List<String>> warnings;
    private final Clock clock;
    private final Consumer<Incident> onClosed;
    private Instant breachSince;

    public IncidentWatcher(Supplier<SloReading> slo, IncidentStore store,
                           Supplier<Optional<ChaosService.ActiveFault>> fault, Supplier<List<String>> pods,
                           Supplier<List<String>> warnings, Clock clock, Consumer<Incident> onClosed) {
        this.slo = slo;
        this.store = store;
        this.fault = fault;
        this.pods = pods;
        this.warnings = warnings;
        this.clock = clock;
        this.onClosed = onClosed;
    }

    /** A drill started: open an incident at once, or add it to the one already open. */
    public synchronized void drillStarted(ChaosService.ActiveFault f) {
        Instant now = clock.instant();
        Incident incident = store.open().orElseGet(() -> {
            Incident i = open(now, "drill");
            i.fault = f.fault();
            return i;
        });
        incident.add(now, "chaos", "fault injected: " + f.fault() + ", ends by itself at " + f.until());
        store.put(incident);
    }

    public synchronized void tick() {
        Instant now = clock.instant();
        SloReading reading = slo.get();
        breachSince = reading.breached() ? (breachSince == null ? now : breachSince) : null;
        Optional<Incident> open = store.open();
        if (open.isEmpty()) {
            if (breachSince != null && !now.isBefore(breachSince.plus(SUSTAINED))) {
                Incident i = open(now, "breach");
                i.add(now, "slo", describe(reading) + " - breached for " + SUSTAINED.toSeconds() + " s");
                i.lastBreached = true;
                store.put(i);
            }
            return;
        }
        Incident incident = open.get();
        follow(incident, reading, now);
        store.put(incident);
        if (!incident.isOpen()) {
            onClosed.accept(incident);
        }
    }

    private void follow(Incident incident, SloReading reading, Instant now) {
        if (reading.available() && (incident.lastBreached == null || incident.lastBreached != reading.breached())) {
            if (reading.breached()) {
                incident.add(now, "slo", "breached: " + describe(reading));
            } else if (Boolean.TRUE.equals(incident.lastBreached)) {
                incident.add(now, "slo", "recovered: " + describe(reading));
            }
            incident.lastBreached = reading.breached();
        }
        List<String> current = pods.get();
        if (incident.knownPods.isEmpty()) {
            incident.knownPods = new ArrayList<>(current);
        } else {
            for (String gone : incident.knownPods) {
                if (!current.contains(gone)) {
                    incident.add(now, "kubernetes", "pod " + gone + " gone");
                }
            }
            for (String added : current) {
                if (!incident.knownPods.contains(added)) {
                    incident.add(now, "kubernetes", "pod " + added + " created");
                }
            }
            incident.knownPods = new ArrayList<>(current);
        }
        for (String warning : warnings.get()) {
            if (!incident.seenWarnings.contains(warning)) {
                incident.seenWarnings.add(warning);
                incident.add(now, "kubernetes", warning);
            }
        }
        boolean faultActive = fault.get().isPresent();
        boolean healthy = reading.available() && !reading.breached() && (reading.hasTraffic() || !faultActive);
        if (!healthy) {
            incident.healthySince = null;
            return;
        }
        if (incident.healthySince == null) {
            incident.healthySince = now;
        }
        if (!now.isBefore(incident.healthySince.plus(HOLD))) {
            incident.status = "resolved";
            incident.resolvedAt = now;
            incident.add(now, "slo", reading.hasTraffic() ? "resolved: both SLOs held for 60 s"
                    : "resolved: the fault has ended and there is no traffic to breach them");
            log.info("incident {} resolved", incident.id);
        }
    }

    private Incident open(Instant now, String kind) {
        Incident i = new Incident();
        i.id = "inc-" + now.getEpochSecond();
        i.kind = kind;
        i.openedAt = now;
        i.status = "open";
        i.knownPods = new ArrayList<>(pods.get());
        log.warn("incident {} opened ({})", i.id, kind);
        return i;
    }

    static String describe(SloReading r) {
        if (!r.hasTraffic()) {
            return "no booking traffic";
        }
        return "success " + (r.successRatio() == null ? "?" : Math.round(r.successRatio() * 1000) / 10.0 + "%")
                + ", p95 " + (r.p95Seconds() == null ? "?" : r.p95Seconds() + " s");
    }
}
