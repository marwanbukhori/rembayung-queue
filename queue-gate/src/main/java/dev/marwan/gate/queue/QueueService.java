package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class QueueService {

    static final String TICKET_COUNTER = "queue:ticket";
    static final String ADMIT_PREFIX = "admit:";

    private final StringRedisTemplate redis;
    private final DropProperties drop;
    private final Clock clock;

    public QueueService(StringRedisTemplate redis, DropProperties drop, Clock clock) {
        this.redis = redis;
        this.drop = drop;
        this.clock = clock;
    }

    public JoinResult join() {
        Instant now = clock.instant();

        if (now.isBefore(drop.opensAt())) {
            throw new DropNotOpenException(Duration.between(now, drop.opensAt()).toSeconds());
        }
        if (now.isAfter(drop.closesAt())) {
            throw new DropClosedException();
        }

        Long ticket = redis.opsForValue().increment(TICKET_COUNTER);
        if (ticket == null || ticket > drop.ticketCap()) {
            throw new SoldOutException();
        }

        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(ADMIT_PREFIX + token, String.valueOf(ticket), drop.ticketTtl());

        long admitted = Admission.admittedBy(now, drop.opensAt(), drop.admitRate());
        long position = Math.max(0, ticket - admitted);

        return new JoinResult(token, ticket, position,
                (double) position / drop.admitRate(),
                position == 0);
    }
}
