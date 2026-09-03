package dev.marwan.gate;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;

@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestBase.FixedClockConfig.class)
public abstract class RedisTestBase {

    /** Drop opens at a fixed instant in every test, so arithmetic is predictable. */
    public static final Instant OPENS_AT = Instant.parse("2026-09-03T13:00:00Z");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("drop.opens-at", () -> OPENS_AT.toString());
        registry.add("drop.closes-at", () -> OPENS_AT.plusSeconds(1800).toString());
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return new TestClock(OPENS_AT);
        }
    }

    @Autowired protected StringRedisTemplate redis;
    @Autowired protected Clock clock;

    protected TestClock clock() {
        return (TestClock) clock;
    }

    /**
     * Redis reuse keeps the container alive between runs, so every test must
     * start from an empty keyspace or fixed ticket numbers would collide.
     */
    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        clock().setNow(OPENS_AT);
    }
}
