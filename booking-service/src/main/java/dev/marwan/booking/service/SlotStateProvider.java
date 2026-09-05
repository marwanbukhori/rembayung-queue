package dev.marwan.booking.service;

import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.repository.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The single place slot state is computed.
 *
 * Deliberately a plain read: findById, not findByIdForUpdate. Taking the
 * pessimistic lock here would put a metrics scrape into contention with real
 * bookings on the one row the whole system serialises on — a monitor that
 * degrades the thing it monitors.
 */
@Service
public class SlotStateProvider {

    private final SlotRepository slotRepository;

    public SlotStateProvider(SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    @Transactional(readOnly = true)
    public Optional<SlotState> stateFor(long slotId) {
        // The id comes from the argument, not slot.getId(): they are the same
        // value for anything findById returned, and reading it back would
        // unbox a Long that is null on an entity that was never persisted.
        return slotRepository.findById(slotId)
                .map(slot -> SlotState.of(slotId, slot.getCapacity(), slot.getSeatsTaken()));
    }

    /**
     * Slots the gauges report on. Every slot in the table — the demo has one,
     * and a restaurant booking system will never have enough rows here for this
     * to be the expensive query.
     */
    @Transactional(readOnly = true)
    public List<Long> trackedSlotIds() {
        // Permanent slots only. stateFor(id) still answers for ANY slot, so the
        // console can read a sandbox it owns — it is the gauges, whose label set
        // must stay bounded, that exclude them.
        return slotRepository.findPermanentSlotIds();
    }
}
