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
            // A bare number is a token issued BEFORE drops existed, when the
            // value was just "{ticket}". Those are in Redis with a live TTL at
            // the moment this version rolls out, and a rolling deploy replaces
            // pods one at a time — so for a few minutes both encodings are in
            // flight at once.
            //
            // Rejecting them would hand a 403 to someone who did nothing wrong,
            // mid-drop, purely because a deploy happened while they were
            // queueing. They belong to the only drop that existed then.
            //
            // Safe to delete once no pre-upgrade token can still be alive:
            // ticketTtl after the rollout completes.
            try {
                return Optional.of(new Held(drops.defaultDrop(), Long.parseLong(raw)));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
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

        Instant startsAt = drops.admissionStartsAt(drop);
        long admitted = Admission.admittedBy(now, startsAt, drop.admitRate());
        long position = Math.max(0, ticket - admitted);
        boolean isAdmitted = position == 0;

        Instant expiresAt = Admission.turnAt(ticket, startsAt, drop.admitRate())
                .plus(drop.admissionWindow());
        long expiresIn = isAdmitted ? Math.max(0, Duration.between(now, expiresAt).toSeconds()) : 0;

        return Optional.of(new PositionView(position, isAdmitted, expiresIn));
    }

    /**
     * Consumes the token, or throws. GETDEL is atomic, so two simultaneous
     * requests carrying the same token race inside Redis and exactly one wins —
     * there is no check-then-act window to exploit.
     */
    public DropRecord consume(String token) {
        String raw = redis.opsForValue().getAndDelete(QueueService.ADMIT_PREFIX + token);
        if (raw == null) {
            throw new TokenRejectedException("TOKEN_INVALID");
        }
        Held held = resolve(raw).orElseThrow(() -> new TokenRejectedException("TOKEN_INVALID"));
        DropRecord drop = held.drop();
        long ticket = held.ticket();
        Instant now = clock.instant();

        Instant startsAt = drops.admissionStartsAt(drop);
        if (!Admission.isAdmitted(ticket, now, startsAt, drop.admitRate())) {
            throw new TokenRejectedException("TOKEN_NOT_YET_ADMITTED");
        }
        if (Admission.hasExpired(ticket, now, startsAt,
                                 drop.admitRate(), drop.admissionWindow())) {
            throw new TokenRejectedException("TOKEN_EXPIRED");
        }
        // Returns the drop rather than void, so the caller can pin the booking to
        // the slot this token was actually issued for. Without that, slotId is
        // whatever the request body says and a sandbox token books slot 1.
        return drop;
    }
}
