package dev.marwan.booking.web;

import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.SlotStateProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cluster-internal only. booking-service has no Route, so nothing outside the
 * namespace can reach these paths — the isolation is structural rather than a
 * check that could be forgotten.
 *
 * Reads go through SlotStateProvider rather than the repository, so the console,
 * the Prometheus gauges and the alert rules all report one computation of seats
 * and oversold. Phase 6 built that provider for exactly this reason.
 */
@RestController
@RequestMapping("/internal/slots")
public class InternalController {

    /** The same 250 seats the drop sells, so a sandbox behaves like the real thing. */
    private static final int SANDBOX_CAPACITY = 250;

    /** Years ahead a sandbox slot may be placed. Only ever a demo date. */
    private static final int SANDBOX_HORIZON_DAYS = 3650;

    private static final int SEED_ATTEMPTS = 5;

    private final SlotStateProvider provider;
    private final SlotRepository slots;

    public InternalController(SlotStateProvider provider, SlotRepository slots) {
        this.provider = provider;
        this.slots = slots;
    }

    /**
     * A fresh 250-seat slot for one visitor's sandbox.
     *
     * The service date and time are drawn at random rather than fixed, because
     * slots carries UNIQUE (service_date, service_time): a constant "thirty days
     * out at 19:00" would serve the first visitor and hand every visitor after
     * them a 500. The collision is rare enough over a decade of minutes to be
     * worth a retry rather than a sequence of its own.
     */
    @PostMapping
    public Map<String, Long> seed() {
        for (int attempt = 1; ; attempt++) {
            try {
                Slot slot = slots.saveAndFlush(new Slot(
                        LocalDate.now().plusDays(
                                1 + ThreadLocalRandom.current().nextInt(SANDBOX_HORIZON_DAYS)),
                        String.format("%02d:%02d",
                                ThreadLocalRandom.current().nextInt(24),
                                ThreadLocalRandom.current().nextInt(60)),
                        SANDBOX_CAPACITY));
                return Map.of("slotId", slot.getId());
            } catch (DataIntegrityViolationException e) {
                if (attempt == SEED_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<SlotState> state(@PathVariable long id) {
        return provider.stateFor(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
