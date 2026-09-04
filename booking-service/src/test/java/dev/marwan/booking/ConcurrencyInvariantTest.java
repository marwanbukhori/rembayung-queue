package dev.marwan.booking;

import dev.marwan.booking.api.BookingRequest;
import dev.marwan.booking.api.SlotSoldOutException;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.BookingService;
import dev.marwan.booking.service.ExpirySweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyInvariantTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private ExpirySweeper expirySweeper;
    @Autowired private SlotRepository slotRepository;

    @Test
    void neverOversellsUnderConcurrentBooking() throws Exception {
        int capacity = 250;
        int contenders = 400;
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2027, 1, 1), "19:00", capacity)).getId();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        for (int i = 0; i < contenders; i++) {
            final int n = i;
            attempts.add(pool.submit(() -> {
                startGun.await();
                try {
                    bookingService.book(new BookingRequest(
                            slotId, "+6012" + n, 1, "concurrent-" + n));
                    return true;
                } catch (SlotSoldOutException e) {
                    return false;
                }
            }));
        }

        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).isTrue();

        int succeeded = 0;
        for (Future<Boolean> attempt : attempts) {
            if (attempt.get()) succeeded++;
        }

        Slot finalSlot = slotRepository.findById(slotId).orElseThrow();
        assertThat(succeeded).isEqualTo(capacity);
        assertThat(finalSlot.getSeatsTaken()).isEqualTo(capacity);
        assertThat(finalSlot.getSeatsTaken()).isLessThanOrEqualTo(finalSlot.getCapacity());
    }

    @Test
    void neverOversellsWhileTheSweeperRunsConcurrently() throws Exception {
        int capacity = 100;
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2027, 1, 2), "19:00", capacity)).getId();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger booked = new AtomicInteger();
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();

        // Bookers.
        for (int i = 0; i < 200; i++) {
            final int n = i;
            tasks.add(pool.submit(() -> {
                startGun.await();
                try {
                    bookingService.book(new BookingRequest(
                            slotId, "+6013" + n, 1, "sweeprace-" + n));
                    booked.incrementAndGet();
                } catch (SlotSoldOutException ignored) {
                    // expected once full
                }
                return null;
            }));
        }

        // Sweepers running against a clock far in the future, so every hold is expirable.
        for (int i = 0; i < 4; i++) {
            tasks.add(pool.submit(() -> {
                startGun.await();
                for (int r = 0; r < 20; r++) {
                    expirySweeper.sweepExpired(Instant.now().plusSeconds(3600));
                }
                return null;
            }));
        }

        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).isTrue();
        for (Future<?> task : tasks) task.get();   // surface any thrown exception

        Slot finalSlot = slotRepository.findById(slotId).orElseThrow();
        assertThat(finalSlot.getSeatsTaken()).isBetween(0, capacity);
    }
}
