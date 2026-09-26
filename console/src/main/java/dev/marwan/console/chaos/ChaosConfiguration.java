package dev.marwan.console.chaos;

import java.time.Clock;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import dev.marwan.console.agent.KubernetesConfigMaps;
import dev.marwan.console.objects.ObjectSource;

/** Wires chaos to the cluster; a started drill is published for the incident watcher to open. */
@Configuration
public class ChaosConfiguration {

    @Bean
    ChaosService chaosService(KubernetesConfigMaps maps, ObjectSource objects, ClusterWrites writes,
                              RestClient bookingClient, ApplicationEventPublisher events, Clock clock) {
        return new ChaosService(maps, () -> objects.pods("booking-service"), writes, bookingClient,
                events::publishEvent, clock);
    }
}
