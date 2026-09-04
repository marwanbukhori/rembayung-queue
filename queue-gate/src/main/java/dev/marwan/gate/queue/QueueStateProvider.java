package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Reads queue state without disturbing it.
 *
 * Note GET, never INCR — a metrics scrape that issued a ticket would consume
 * one of the 250 the drop has to give away, and the scrape interval would
 * quietly set the pace at which the queue sold out.
 */
@Service
public class QueueStateProvider {

    private final StringRedisTemplate redis;
    private final DropProperties drop;
    private final Clock clock;

    public QueueStateProvider(StringRedisTemplate redis, DropProperties drop, Clock clock) {
        this.redis = redis;
        this.drop = drop;
        this.clock = clock;
    }

    public QueueState current() {
        String raw = redis.opsForValue().get(QueueService.TICKET_COUNTER);
        long issued = raw == null ? 0L : Long.parseLong(raw);
        long admitted = Admission.admittedBy(clock.instant(), drop.opensAt(), drop.admitRate());
        return QueueState.of(issued, admitted, drop.ticketCap());
    }
}
