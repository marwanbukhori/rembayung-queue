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

    /**
     * Deletes expired sandbox slots and the bookings that belong to them.
     *
     * Takes the slot's row lock first, which the previous version did not, and
     * bookings.slot_id has a foreign key to slots.id. Without the lock a booking
     * could land between the two deletes: the bookings for the slot are removed,
     * a concurrent book() takes the slot lock and inserts a fresh row, and then
     * the slot delete fails with ORA-02292 and rolls back the whole pass - every
     * other expired slot in the batch included - leaving nothing reaped until
     * the next one five minutes later.
     *
     * Locking first removes the gap. A booking either commits before the lock is
     * taken, and is deleted with the rest, or waits for the slot to be gone and
     * then finds nothing to book, which is a 404 and the honest answer for a
     * sandbox that has expired.
     */
    @Transactional
    public int sweepExpired(Instant now) {
        List<Long> expired = slots.findExpiredSandboxIds(now);
        int removed = 0;
        for (Long slotId : expired) {
            // Gone already - another replica's sweeper got there first.
            if (slots.findByIdForUpdate(slotId).isEmpty()) {
                continue;
            }
            bookings.deleteBySlotId(slotId);
            slots.deleteById(slotId);
            removed++;
        }
        return removed;
    }
}
