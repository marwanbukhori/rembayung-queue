package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AdmissionService {

    private final StringRedisTemplate redis;
    private final DropProperties drop;
    private final Clock clock;

    public AdmissionService(StringRedisTemplate redis, DropProperties drop, Clock clock) {
        this.redis = redis;
        this.drop = drop;
        this.clock = clock;
    }

    /** Read-only. Returns empty when the token is unknown or has lapsed from Redis. */
    public Optional<PositionView> position(String token) {
        String raw = redis.opsForValue().get(QueueService.ADMIT_PREFIX + token);
        if (raw == null) {
            return Optional.empty();
        }
        long ticket = Long.parseLong(raw);
        Instant now = clock.instant();

        long admitted = Admission.admittedBy(now, drop.opensAt(), drop.admitRate());
        long position = Math.max(0, ticket - admitted);
        boolean isAdmitted = position == 0;

        Instant expiresAt = Admission.turnAt(ticket, drop.opensAt(), drop.admitRate())
                .plus(drop.admissionWindow());
        long expiresIn = isAdmitted ? Math.max(0, Duration.between(now, expiresAt).toSeconds()) : 0;

        return Optional.of(new PositionView(position, isAdmitted, expiresIn));
    }

    /**
     * Consumes the token, or throws. GETDEL is atomic, so two simultaneous
     * requests carrying the same token race inside Redis and exactly one wins —
     * there is no check-then-act window to exploit.
     */
    public void consume(String token) {
        String raw = redis.opsForValue().getAndDelete(QueueService.ADMIT_PREFIX + token);
        if (raw == null) {
            throw new TokenRejectedException("TOKEN_INVALID");
        }
        long ticket = Long.parseLong(raw);
        Instant now = clock.instant();

        if (!Admission.isAdmitted(ticket, now, drop.opensAt(), drop.admitRate())) {
            throw new TokenRejectedException("TOKEN_NOT_YET_ADMITTED");
        }
        if (Admission.hasExpired(ticket, now, drop.opensAt(),
                                 drop.admitRate(), drop.admissionWindow())) {
            throw new TokenRejectedException("TOKEN_EXPIRED");
        }
    }
}
