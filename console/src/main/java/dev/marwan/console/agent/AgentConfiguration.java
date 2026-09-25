package dev.marwan.console.agent;

import java.time.Clock;
import java.util.Optional;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import dev.marwan.console.cluster.KubernetesAccess;
import dev.marwan.console.metrics.RangeQuery;
import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.state.DemoStateProvider;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClientException;

/** Wires the agent from the services the inspector already uses; no new client of its own. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {

    static final java.nio.file.Path TOKEN = java.nio.file.Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");

    @Bean
    Model agentModel(AgentProperties properties) {
        if (!mayReceiveToken(properties.baseUrl())) {
            return new OpenAiModel(properties.baseUrl(), properties.model());
        }
        // Read per call: the projected token rotates.
        return new OpenAiModel(properties.baseUrl(), properties.model(), () -> {
            try {
                return java.nio.file.Files.readString(TOKEN).trim();
            } catch (java.io.IOException e) {
                return null;
            }
        });
    }

    /** The console's token goes only to an https service inside this cluster, never to an outside host. */
    static boolean mayReceiveToken(String baseUrl) {
        try {
            java.net.URI uri = java.net.URI.create(baseUrl);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getHost().endsWith(".svc.cluster.local");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Bean
    Analyst analyst(ObjectSource objects, RangeQuery prometheus, DemoStateProvider state, Model agentModel,
                    Clock clock) {
        Baseline baseline = new Baseline(objects, prometheus, state::currentFor);
        return new Analyst(baseline::gather, new Tools(objects, prometheus), agentModel, clock);
    }

    @Bean
    AnalysisStore analysisStore(KubernetesAccess kubernetes) {
        return new AnalysisStore(new AnalysisStore.ConfigMapPort() {
            @Override
            public Optional<ConfigMap> get(String name) {
                return Optional.ofNullable(kubernetes.client().configMaps().inNamespace(kubernetes.namespace())
                        .withName(name).get());
            }

            @Override
            public void create(ConfigMap map) {
                try {
                    kubernetes.client().configMaps().inNamespace(kubernetes.namespace()).resource(map).create();
                } catch (KubernetesClientException e) {
                    if (e.getCode() == 409) {
                        throw new AnalysisStore.Conflict();   // another console pod created it first
                    }
                    throw e;
                }
            }

            @Override
            public void update(ConfigMap map) {
                try {
                    kubernetes.client().configMaps().inNamespace(kubernetes.namespace()).resource(map).update();
                } catch (KubernetesClientException e) {
                    if (e.getCode() == 409) {
                        throw new AnalysisStore.Conflict();
                    }
                    throw e;
                }
            }
        });
    }

    @Bean(destroyMethod = "close")
    RunAnalyst runAnalyst(ObjectSource objects, Analyst analyst, AnalysisStore analysisStore, Clock clock) {
        return new RunAnalyst(objects, analyst, analysisStore, clock);
    }

    @Bean
    AgentSchedule agentSchedule(AgentProperties properties, RunAnalyst runAnalyst) {
        return new AgentSchedule(properties, runAnalyst);
    }

    /** The tick, kept apart so switching the agent off stops only this. */
    static class AgentSchedule {
        private final AgentProperties properties;
        private final RunAnalyst runAnalyst;

        AgentSchedule(AgentProperties properties, RunAnalyst runAnalyst) {
            this.properties = properties;
            this.runAnalyst = runAnalyst;
        }

        @Scheduled(fixedDelayString = "${console.agent.interval:10s}", initialDelayString = "30s")
        void tick() {
            if (properties.enabled()) {
                runAnalyst.tick();
            }
        }
    }
}
