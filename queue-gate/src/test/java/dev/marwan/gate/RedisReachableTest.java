package dev.marwan.gate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisReachableTest extends RedisTestBase {

    @Test
    void redisRespondsAndTheClockIsControllable() {
        redis.opsForValue().set("smoke", "ok");
        assertThat(redis.opsForValue().get("smoke")).isEqualTo("ok");
        assertThat(clock.instant()).isEqualTo(OPENS_AT);
    }
}
