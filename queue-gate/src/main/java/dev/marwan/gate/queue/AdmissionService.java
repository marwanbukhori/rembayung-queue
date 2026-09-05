package dev.marwan.gate.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AdmissionService {

    private final StringRedisTemplate redis;
    private final DropRegistry drops;
    private final Clock clock;

    public AdmissionService(StringRedisTemplate redis, DropRegistry drops, Clock clock) {
        this.redis = redis;
        this.drops = drops;
        this.clock = clock;
    }

    /** A ticket together with the drop whose rules govern it. */
    private record Held(DropRecord drop, long ticket) { }

    /**
     * Splits "{dropId}:{ticket}" and loads the drop it names.
     *
     * Empty when the value is not in that shape or the drop it names is gone.
     * A token whose drop has expired is indistinguishable, to a caller, from a
     * token that never existed, so both resolve to nothing rather than to a
     * third outcome the caller could not act on differently.
     */
    private Optional<Held> resolve(String raw) {
        int sep = raw.lastIndexOf(':');
        if (sep < 0) {
            return Optional.empty();
        }
        long ticket;
        try {
            ticket = Long.parseLong(raw.substring(sep + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return drops.find(raw.substring(0, sep)).map(d -> new Held(d, ticket));
    }

    /** Read-only. Returns empty when the token is unknown or has lapsed from Redis. */
    public Optional<PositionView> position(String token) {
        String raw = redis.opsForValue().get(QueueService.ADMIT_PREFIX + token);
        if (raw == null) {
            return Optional.empty();
        }
        Optional<Held> resolved = resolve(raw);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        DropRecord drop = resolved.get().drop();
        long ticket = resolved.get().ticket();
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
        Held held = resolve(raw).orElseThrow(() -> new TokenRejectedException("TOKEN_INVALID"));
        DropRecord drop = held.drop();
        long ticket = held.ticket();
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
