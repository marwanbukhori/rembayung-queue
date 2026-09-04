package dev.marwan.gate;

import dev.marwan.gate.queue.Admission;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionTest {

    private static final Instant OPENS = Instant.parse("2026-09-03T13:00:00Z");
    private static final int RATE = 200;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Test
    void nobodyIsAdmittedBeforeTheDropOpens() {
        assertThat(Admission.admittedBy(OPENS.minusSeconds(1), OPENS, RATE)).isZero();
        assertThat(Admission.admittedBy(OPENS.minusSeconds(3600), OPENS, RATE)).isZero();
    }

    @Test
    void admissionAdvancesAtTheConfiguredRate() {
        assertThat(Admission.admittedBy(OPENS, OPENS, RATE)).isZero();
        assertThat(Admission.admittedBy(OPENS.plusSeconds(1), OPENS, RATE)).isEqualTo(200);
        assertThat(Admission.admittedBy(OPENS.plusSeconds(10), OPENS, RATE)).isEqualTo(2000);
    }

    @Test
    void admissionAdvancesWithinASecond() {
        assertThat(Admission.admittedBy(OPENS.plusMillis(500), OPENS, RATE)).isEqualTo(100);
    }

    @Test
    void aTicketIsAdmittedOnceAdmissionReachesIt() {
        assertThat(Admission.isAdmitted(200, OPENS.plusMillis(999), OPENS, RATE)).isFalse();
        assertThat(Admission.isAdmitted(200, OPENS.plusSeconds(1), OPENS, RATE)).isTrue();
        assertThat(Admission.isAdmitted(201, OPENS.plusSeconds(1), OPENS, RATE)).isFalse();
    }

    @Test
    void turnAtIsTheInstantATicketBecomesAdmitted() {
        assertThat(Admission.turnAt(200, OPENS, RATE)).isEqualTo(OPENS.plusSeconds(1));
        assertThat(Admission.turnAt(100, OPENS, RATE)).isEqualTo(OPENS.plusMillis(500));
    }

    @Test
    void theAdmissionWindowExpiresFiveMinutesAfterTheTurn() {
        Instant turn = Admission.turnAt(200, OPENS, RATE);
        assertThat(Admission.hasExpired(200, turn, OPENS, RATE, WINDOW)).isFalse();
        assertThat(Admission.hasExpired(200, turn.plus(WINDOW), OPENS, RATE, WINDOW)).isFalse();
        assertThat(Admission.hasExpired(200, turn.plus(WINDOW).plusSeconds(1), OPENS, RATE, WINDOW)).isTrue();
    }

    @Test
    void admissionIsIdenticalForEveryCaller() {
        // The property that removes the need for coordination between replicas:
        // the same inputs always produce the same answer, with no shared state.
        Instant now = OPENS.plusMillis(1234);
        long first = Admission.admittedBy(now, OPENS, RATE);
        long second = Admission.admittedBy(now, OPENS, RATE);
        assertThat(first).isEqualTo(second).isEqualTo(246);
    }

    @Test
    void aTicketIsAdmittedAtItsOwnTurnForRatesThatDoNotDivideAThousand() {
        // Floor division silently broke this: at rate 300, turnAt(1) was 3ms
        // but admittedBy(3ms) was 0, so ticket 1 was never admitted at its turn.
        for (int rate : new int[] { 150, 200, 250, 300, 333 }) {
            for (long ticket : new long[] { 1, 7, 199, 200, 201, 400 }) {
                Instant turn = Admission.turnAt(ticket, OPENS, rate);
                assertThat(Admission.admittedBy(turn, OPENS, rate))
                        .as("rate=%d ticket=%d", rate, ticket)
                        .isGreaterThanOrEqualTo(ticket);
            }
        }
    }
}
