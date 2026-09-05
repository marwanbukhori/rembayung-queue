package dev.marwan.console;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * The console's two HTTP clients, each pointed at a service that owns numbers
 * the console is not allowed to compute for itself.
 *
 * Both carry an explicit, short timeout. The default is no timeout at all,
 * which would let one wedged service hold a request thread per viewer per poll
 * until the container ran out — the failure that turns a monitoring page into
 * part of the incident.
 */
@Configuration
@EnableConfigurationProperties(ConsoleProperties.class)
public class ConsoleConfiguration {

    @Bean
    RestClient bookingClient(ConsoleProperties properties) {
        return client(properties.bookingBaseUrl(), properties);
    }

    @Bean
    RestClient gateClient(ConsoleProperties properties) {
        return client(properties.gateBaseUrl(), properties);
    }

    private RestClient client(String baseUrl, ConsoleProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** Injected rather than called statically so cache expiry is testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
