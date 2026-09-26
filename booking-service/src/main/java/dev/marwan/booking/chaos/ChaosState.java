package dev.marwan.booking.chaos;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * A fault this service applies to itself on request, and takes back by itself.
 *
 * Two faults, both about the database the booking path depends on: holding
 * each booking's connection longer, or shrinking the pool of connections.
 * Either one is what an incident on this system actually looks like.
 *
 * The revert does not depend on whoever started the fault. A scheduled check
 * here restores the defaults once the fault's time is up, so a console that
 * crashes mid-drill cannot leave the booking path broken.
 */
public class ChaosState {

    private static final Logger log = LoggerFactory.getLogger(ChaosState.class);

    public static final int MAX_SECONDS = 120;
    public static final long SLOW_MILLIS = 400;
    static final int SQUEEZED_POOL = 1;

    /** The pool's size, reachable at runtime. */
    public interface PoolSizer {
        int size();

        void resize(int n);
    }

    public record Active(String fault, Instant until) { }

    private final PoolSizer pool;
    private final Clock clock;
    private String fault;
    private Instant until;
    private int savedPool = -1;

    public ChaosState(PoolSizer pool, Clock clock) {
        this.pool = pool;
        this.clock = clock;
    }

    public synchronized Active start(String id, int seconds) {
        if (!"slow-database".equals(id) && !"squeeze-pool".equals(id)) {
            throw new IllegalArgumentException("unknown fault: " + id);
        }
        restore();
        int capped = Math.max(1, Math.min(seconds, MAX_SECONDS));
        fault = id;
        until = clock.instant().plusSeconds(capped);
        if (id.equals("squeeze-pool")) {
            savedPool = pool.size();
            pool.resize(SQUEEZED_POOL);
        }
        log.warn("chaos: {} until {}", id, until);
        return new Active(fault, until);
    }

    public synchronized void clear() {
        restore();
    }

    public synchronized Optional<Active> current() {
        return fault == null ? Optional.empty() : Optional.of(new Active(fault, until));
    }

    /** How long a booking should hold its connection before committing. */
    public synchronized long delayMillis() {
        return "slow-database".equals(fault) && clock.instant().isBefore(until) ? SLOW_MILLIS : 0;
    }

    @Scheduled(fixedDelay = 2000)
    public synchronized void revertIfExpired() {
        if (fault != null && !clock.instant().isBefore(until)) {
            log.warn("chaos: {} expired, restoring", fault);
            restore();
        }
    }

    private void restore() {
        if ("squeeze-pool".equals(fault) && savedPool > 0) {
            pool.resize(savedPool);
        }
        fault = null;
        until = null;
        savedPool = -1;
    }
}
