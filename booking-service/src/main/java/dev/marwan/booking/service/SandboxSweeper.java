package dev.marwan.booking.service;

import dev.marwan.booking.repository.BookingRepository;
import dev.marwan.booking.repository.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Deletes console sandbox slots once they lapse, along with their bookings.
 *
 * Sessions are unlimited by design — a visitor may create and discard drops all
 * afternoon — so without this every abandoned demo leaves a permanent row in a
 * database with finite free-tier storage. Redis expires the drop record on its
 * own after 30 minutes of idleness; this is the Oracle half of the same idea.
 *
 * The bookings go first and the slot second, because bookings reference the slot
 * and the database would refuse the reverse order. That ordering is the whole
 * subtlety here.
 */
@Service
public class SandboxSweeper {

    private static final Logger log = LoggerFactory.getLogger(SandboxSweeper.class);

    private final SlotRepository slots;
    private final BookingRepository bookings;

    public SandboxSweeper(SlotRepository slots, BookingRepository bookings) {
        this.slots = slots;
        this.bookings = bookings;
    }

    /**
     * @Transactional is on the SCHEDULED method, not only on the worker below.
     *
     * ExpirySweeper had this exact bug and it went unnoticed for six phases: a
     * @Scheduled method calling another method of the same bean goes straight to
     * `this`, never entering Spring's proxy, so @Transactional silently does not
     * apply. It threw "No active transaction" 197 times on one production pod
     * while every test passed, because the tests called the inner method
     * directly and therefore did go through the proxy.
     */
    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void sweep() {
        int removed = sweepExpired(Instant.now());
        if (removed > 0) {
            log.info("Reaped {} expired sandbox slots", removed);
        }
    }

    @Transactional
    public int sweepExpired(Instant now) {
        List<Long> expired = slots.findExpiredSandboxIds(now);
        for (Long slotId : expired) {
            bookings.deleteBySlotId(slotId);
            slots.deleteById(slotId);
        }
        return expired.size();
    }
}
