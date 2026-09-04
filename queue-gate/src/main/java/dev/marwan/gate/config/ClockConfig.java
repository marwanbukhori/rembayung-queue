package dev.marwan.gate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * Admission is derived from the current time, so the clock is injected
     * rather than read statically. Tests supply a settable clock marked
     * @Primary; production code must never call Instant.now() directly.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
