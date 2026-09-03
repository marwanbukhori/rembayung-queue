package dev.marwan.gate;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * Supplies the settable test clock.
 *
 * Deliberately a top-level class rather than nested inside RedisTestBase:
 * Spring auto-detects nested @Configuration classes as "default configuration
 * classes", which currently produces a warning and would cause duplicate
 * registration from Spring Framework 7.1 onward. As a top-level class it is
 * registered exactly once, by the @Import on RedisTestBase.
 *
 * @Primary makes injection deterministic: the production ClockConfig also
 * defines a Clock, and bean-definition order is not guaranteed.
 */
@TestConfiguration
public class FixedClockConfig {

    @Bean
    @Primary
    Clock testClock() {
        return new TestClock(RedisTestBase.OPENS_AT);
    }
}
