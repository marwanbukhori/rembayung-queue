package dev.marwan.booking;

import dev.marwan.booking.api.BookingRequest;
import dev.marwan.booking.api.BookingResult;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;

    @Test
    void replayingTheSameKeyReturnsTheOriginalBookingAndTakesNoExtraSeats() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 5), "19:00", 250)).getId();

        BookingRequest request =
                new BookingRequest(slotId, "+60123456789", 3, "idem-fixed-key");

        BookingResult first = bookingService.book(request);
        BookingResult replay = bookingService.book(request);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.bookingId()).isEqualTo(first.bookingId());
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(3);
    }

    /**
     * The same key submitted twice at once, which is what a double-tapped button
     * and a client retry on a slow response both look like from here.
     *
     * The sequential test above passes because the second call sees the first
     * one's committed row. Concurrently there is no such row to see: both calls
     * read "no prior booking", both claim a seat, and both insert. The unique
     * constraint on idempotency_key stops the second seat from being sold - the
     * transaction rolls back and the seat is returned - so this cannot oversell.
     * What it can do is answer a retry with HTTP 500, which is the opposite of
     * what an idempotency key is for.
     */
    @Test
    void theSameKeySubmittedConcurrentlyIsStillOneBooking() throws Exception {
        int pairs = 40;
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 6), "19:00", 250)).getId();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<BookingResult>> results = new ArrayList<>();

        for (int i = 0; i < pairs; i++) {
            BookingRequest request =
                    new BookingRequest(slotId, "+6014" + i, 1, "idem-race-" + i);
            for (int copy = 0; copy < 2; copy++) {
                results.add(pool.submit(() -> {
                    startGun.await();
                    return bookingService.book(request);
                }));
            }
        }

        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

        // Every submission answers, and the two copies of a key agree on which
        // booking they made. get() rethrows whatever the call threw, so a 500
        // from the duplicate insert fails here rather than being counted.
        for (Future<BookingResult> result : results) {
            assertThat(result.get().bookingId()).isNotNull();
        }
        for (int i = 0; i < pairs; i++) {
            assertThat(results.get(2 * i).get().bookingId())
                    .isEqualTo(results.get(2 * i + 1).get().bookingId());
        }

        // One key, one seat. 40 keys, 40 seats.
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken())
                .isEqualTo(pairs);
    }
}
