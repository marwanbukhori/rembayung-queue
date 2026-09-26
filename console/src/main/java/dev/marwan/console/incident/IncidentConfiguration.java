package dev.marwan.console.incident;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import dev.marwan.console.agent.KubernetesConfigMaps;
import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.slo.SloService;

/**
 * Wires the incident watcher to the SLOs, the chaos lock and the cluster, and
 * ticks it. Off unless switched on, like the run agent, so a console outside
 * the cluster - or a test - never polls a namespace it cannot see.
 */
@Configuration
public class IncidentConfiguration {

    static final List<String> WATCHED = List.of("booking-service", "queue-gate");

    @Bean
    IncidentStore incidentStore(KubernetesConfigMaps maps) {
        return new IncidentStore(maps);
    }

    /** What happens when an incident closes; the commander replaces this with its postmortem. */
    @Bean
    IncidentHooks incidentHooks() {
        return new IncidentHooks();
    }

    @Bean
    IncidentWatcher incidentWatcher(SloService slo, IncidentStore store, ChaosService chaos, ObjectSource objects,
                                    IncidentHooks hooks, Clock clock) {
        return new IncidentWatcher(slo::now, store, chaos::current,
                () -> podNames(objects), () -> warnings(objects, clock), clock, hooks::closed);
    }

    @Bean
    Remediation remediation(dev.marwan.console.chaos.ClusterWrites writes, ChaosService chaos, IncidentWatcher watcher,
                            IncidentStore store, Clock clock) {
        return new Remediation(writes, chaos, watcher, store, clock);
    }

    @Bean
    IncidentTicker incidentTicker(IncidentWatcher watcher, Remediation remediation,
                                  @Value("${console.incidents.enabled:false}") boolean enabled) {
        return new IncidentTicker(watcher, remediation, enabled);
    }

    /** The commander asks through the console's own MCP endpoint, like the run agent. */
    @Bean
    IncidentCommander incidentCommander(dev.marwan.console.agent.Tools agentTools,
                                        dev.marwan.console.agent.Model agentModel, SloService slo, ChaosService chaos,
                                        IncidentStore store, IncidentWatcher watcher, Clock clock,
                                        dev.marwan.console.auth.AccessKey key,
                                        @Value("${server.port:8082}") int port) {
        return new IncidentCommander(new dev.marwan.console.agent.McpToolCaller("http://localhost:" + port + "/mcp",
                key.value(), agentTools), agentModel, slo::now, chaos::current, store, watcher, clock);
    }

    @Bean
    CommanderTicker commanderTicker(IncidentCommander commander, IncidentWatcher watcher, IncidentHooks hooks,
                                    dev.marwan.console.agent.Model agentModel,
                                    @Value("${console.incidents.enabled:false}") boolean enabled) {
        PostmortemWriter writer = new PostmortemWriter(agentModel);
        // Written off the watcher's lock: a model call can take a minute.
        hooks.onClosed(closed -> Thread.ofVirtual().start(() -> {
            Incident.Postmortem pm = writer.write(closed);
            watcher.update(closed.id, i -> i.postmortem = pm);
        }));
        return new CommanderTicker(commander, enabled);
    }

    /** One commander cycle every 30 s while an incident is open. */
    public static class CommanderTicker {
        private final IncidentCommander commander;
        private final boolean enabled;

        CommanderTicker(IncidentCommander commander, boolean enabled) {
            this.commander = commander;
            this.enabled = enabled;
        }

        @Scheduled(fixedDelayString = "30s", initialDelayString = "30s")
        void cycle() {
            if (enabled) {
                try {
                    commander.cycle();
                } catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(CommanderTicker.class).warn("commander cycle skipped: {}", e.toString());
                }
            }
        }
    }

    static List<String> podNames(ObjectSource objects) {
        List<String> out = new ArrayList<>();
        for (String app : WATCHED) {
            objects.pods(app).stream().map(p -> p.getMetadata().getName()).sorted().forEach(out::add);
        }
        return out;
    }

    /** Warning events on the watched Deployments and their pods from the last ten minutes, one line each. */
    static List<String> warnings(ObjectSource objects, Clock clock) {
        Instant since = clock.instant().minusSeconds(600);
        List<String> out = new ArrayList<>();
        for (String app : WATCHED) {
            collect(objects.events("Deployment", app), "Deployment/" + app, since, out);
            for (Pod pod : objects.pods(app)) {
                String name = pod.getMetadata().getName();
                collect(objects.events("Pod", name), "Pod/" + name, since, out);
            }
        }
        return out;
    }

    private static void collect(List<Event> events, String object, Instant since, List<String> out) {
        for (Event e : events) {
            String at = e.getLastTimestamp();
            if ("Warning".equals(e.getType()) && at != null && Instant.parse(at).isAfter(since)) {
                out.add(e.getReason() + " on " + object + ": " + e.getMessage());
            }
        }
    }

    /** Hooks other parts of the console fill in. */
    public static class IncidentHooks {
        private Consumer<Incident> onClosed = i -> { };

        public void onClosed(Consumer<Incident> hook) {
            this.onClosed = hook;
        }

        void closed(Incident incident) {
            onClosed.accept(incident);
        }
    }

    /** The schedule and the drill listener, both inert unless incidents are enabled. */
    public static class IncidentTicker {
        private final IncidentWatcher watcher;
        private final Remediation remediation;
        private final boolean enabled;

        IncidentTicker(IncidentWatcher watcher, Remediation remediation, boolean enabled) {
            this.watcher = watcher;
            this.remediation = remediation;
            this.enabled = enabled;
        }

        @Scheduled(fixedDelayString = "15s", initialDelayString = "20s")
        void tick() {
            if (enabled) {
                try {
                    watcher.tick();
                    remediation.revertDue();
                } catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(IncidentTicker.class).warn("incident tick skipped: {}", e.toString());
                }
            }
        }

        @EventListener
        void drillStarted(ChaosService.ActiveFault fault) {
            if (enabled) {
                watcher.drillStarted(fault);
            }
        }
    }
}
