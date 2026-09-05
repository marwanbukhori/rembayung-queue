package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores drops in Redis so a new one costs a write rather than a redeploy.
 *
 * Idle drops expire on their own: every read refreshes the key's TTL, so a
 * sandbox someone abandoned disappears 30 minutes later without a sweeper.
 * Letting Redis do the expiry is what makes unlimited retries affordable — a
 * visitor can create and discard drops all afternoon and leave nothing behind.
 */
@Service
public class DropRegistry {

    public static final String DEFAULT_ID = "default";

    private static final String KEY_PREFIX = "drop:";
    private static final Duration IDLE_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final DropProperties properties;
    private final Clock clock;
    // Spring Boot 4 ships Jackson 3, which handles java.time out of the box.
    // Jackson 2's jackson-datatype-jsr310 is not on this classpath at all, so the
    // Jackson 2 mapper cannot write an Instant however many modules it looks for.
    private final ObjectMapper json = new ObjectMapper();

    public DropRegistry(StringRedisTemplate redis, DropProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The canonical drop, built from configuration rather than stored.
     *
     * Deliberately not persisted: it is whatever the running configuration says,
     * so `open-drop.sh` and the ConfigMap keep working exactly as before and
     * this task cannot break the 21:00 path.
     */
    public DropRecord defaultDrop() {
        return new DropRecord(
                DEFAULT_ID,
                properties.opensAt(),
                properties.closesAt(),
                properties.ticketCap(),
                properties.admitRate(),
                properties.admissionWindow(),
                properties.ticketTtl(),
                null);
    }

    public DropRecord create(int admitRate, Long slotId) {
        Instant now = clock.instant();
        DropRecord drop = new DropRecord(
                "d-" + UUID.randomUUID().toString().substring(0, 8),
                // Opens now. A visitor will not wait until 21:00 to see a demo,
                // and admission is a pure function of elapsed time since opensAt.
                now,
                now.plus(Duration.ofHours(1)),
                properties.ticketCap(),
                admitRate,
                properties.admissionWindow(),
                properties.ticketTtl(),
                slotId);
        write(drop);
        return drop;
    }

    /**
     * Change a stored drop's admission rate.
     *
     * The point of holding drops in Redis: the rate used to be an environment
     * variable, so changing it meant a redeploy. Now it is a write, and a
     * visitor can push their own drop from 8 to 200 admissions a second and
     * watch the connection pool give out while the seat invariant does not.
     *
     * The canonical drop is deliberately not updatable. It is built from
     * configuration by {@link #defaultDrop()} and has no key in Redis, which is
     * what keeps the scheduled 21:00 path driven by the ConfigMap and
     * open-drop.sh rather than by whatever the last person to press a button
     * chose. Writing it here would create a stored record that silently
     * outranked the configuration — a drop whose real rate you could no longer
     * read from the deployment.
     *
     * @return the updated record, or empty if no such stored drop exists
     * @throws IllegalArgumentException if asked to change the canonical drop
     */
    public Optional<DropRecord> updateAdmitRate(String id, int admitRate) {
        if (DEFAULT_ID.equals(id)) {
            throw new IllegalArgumentException(
                    "the canonical drop's admission rate is configuration, not a stored value");
        }
        if (admitRate <= 0) {
            throw new IllegalArgumentException("admitRate must be positive but was " + admitRate);
        }
        return find(id).map(existing -> {
            DropRecord updated = new DropRecord(
                    existing.id(),
                    existing.opensAt(),
                    existing.closesAt(),
                    existing.ticketCap(),
                    admitRate,
                    existing.admissionWindow(),
                    existing.ticketTtl(),
                    existing.slotId());
            write(updated);
            return updated;
        });
    }

    public Optional<DropRecord> find(String id) {
        if (DEFAULT_ID.equals(id)) {
            return Optional.of(defaultDrop());
        }
        String raw = redis.opsForValue().get(KEY_PREFIX + id);
        if (raw == null) {
            return Optional.empty();
        }
        touch(id);
        try {
            return Optional.of(json.readValue(raw, DropRecord.class));
        } catch (Exception e) {
            // A record we cannot parse is indistinguishable from one that is
            // gone, as far as a caller is concerned. Treating it as absent keeps
            // a corrupt key from turning every request into a 500.
            return Optional.empty();
        }
    }

    /**
     * Refreshes the idle timer. Any read counts as activity.
     *
     * Both keys, because a drop is not just its record: the ticket counter is
     * created by INCR, which sets no expiry of its own, so without this every
     * sandbox drop ever started would leave one key in Redis permanently. They
     * are refreshed together so the counter cannot expire while the drop that
     * owns it is still being read - a counter that vanished early would restart
     * ticket numbering at 1 and admit a second visitor into a taken seat's
     * position.
     *
     * The canonical drop is skipped for both: it is never stored in Redis and
     * never expires, and neither should the tickets it has issued.
     */
    public void touch(String id) {
        if (!DEFAULT_ID.equals(id)) {
            redis.expire(KEY_PREFIX + id, IDLE_TTL);
            redis.expire(QueueService.ticketCounter(id), IDLE_TTL);
        }
    }

    private void write(DropRecord drop) {
        try {
            redis.opsForValue().set(KEY_PREFIX + drop.id(),
                    json.writeValueAsString(drop), IDLE_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("could not store drop " + drop.id(), e);
        }
    }
}
