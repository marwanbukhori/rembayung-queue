package dev.marwan.console.agent;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import dev.marwan.console.metrics.RangeQuery;
import dev.marwan.console.objects.ObjectSource;
import dev.marwan.console.state.DemoStateProvider;
import io.fabric8.kubernetes.api.model.ConfigMap;

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

    /** The read-only tools, shared by the run agent and the MCP server. */
    @Bean
    Tools agentTools(ObjectSource objects, RangeQuery prometheus) {
        return new Tools(objects, prometheus);
    }

    @Bean
    Analyst analyst(ObjectSource objects, RangeQuery prometheus, DemoStateProvider state, Model agentModel, Tools agentTools,
                    Clock clock, dev.marwan.console.ConsoleProperties console, dev.marwan.console.auth.AccessKey key,
                    @org.springframework.beans.factory.annotation.Value("${server.port:8082}") int port) {
        Baseline baseline = new Baseline(objects, prometheus, console.pool().perReplica(), state::currentFor);
        // The agent asks through the console's own MCP endpoint, over loopback, like any other client.
        ToolCaller viaMcp = new McpToolCaller("http://localhost:" + port + "/mcp", key.value(), agentTools);
        return new Analyst(baseline::gather, viaMcp, agentModel, clock);
    }

    @Bean
    AnalysisStore analysisStore(KubernetesConfigMaps maps) {
        return new AnalysisStore(maps);
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
