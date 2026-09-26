package dev.marwan.console.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class K6SummaryTest {

    @Test
    void readsTheSummaryLineAmongOtherOutput() {
        String log = """
                2026-09-25T13:40:00Z running (0m05s), 200/200 VUs
                K6_SUMMARY {"vus":200,"iterations":200,"booked":196,"rejected":4,"notClean":4,"p50":120.5,"p95":2100,"max":3050.2,"durationMs":61234}
                2026-09-25T13:41:01Z done
                """;

        K6Summary summary = K6Summary.parse(log).orElseThrow();

        assertThat(summary.vus()).isEqualTo(200);
        assertThat(summary.booked()).isEqualTo(196);
        assertThat(summary.notClean()).isEqualTo(4);
        assertThat(summary.p95()).isEqualTo(2100.0);
        assertThat(summary.durationMs()).isEqualTo(61234L);
    }

    @Test
    void readsTheCustomerFunnelAndOutcomes() {
        String log = "K6_SUMMARY {\"vus\":200,\"booked\":88,\"joined\":195,\"admitted\":90,\"soldOutAtJoin\":5,"
                + "\"gaveUp\":105,\"refusedAfterAdmission\":0,\"soldOut\":0,\"overloaded\":2,\"faults\":0,"
                + "\"queueWaitP50\":44,\"queueWaitP95\":86,\"queueWaitMax\":89,\"partySize\":2,\"patienceSeconds\":90}";

        K6Summary s = K6Summary.parse(log).orElseThrow();

        assertThat(s.hasOutcomes()).isTrue();
        assertThat(s.joined()).isEqualTo(195);
        assertThat(s.gaveUp()).isEqualTo(105);
        assertThat(s.overloaded()).isEqualTo(2);
        assertThat(s.queueWaitP95()).isEqualTo(86);
        assertThat(s.partySize()).isEqualTo(2);
        assertThat(s.patienceSeconds()).isEqualTo(90);
    }

    @Test
    void readsEachWaveOfATwoWaveRun() {
        String log = "K6_SUMMARY {\"vus\":200,\"booked\":150,\"joined\":200,\"admitted\":165,\"gaveUp\":35,"
                + "\"waves\":2,\"perWave\":[{\"wave\":1,\"vus\":100,\"joined\":100,\"admitted\":70,\"booked\":60,"
                + "\"gaveUp\":30,\"overloaded\":10,\"p95\":2100,\"max\":4000},{\"wave\":2,\"vus\":100,\"joined\":100,"
                + "\"admitted\":95,\"booked\":90,\"gaveUp\":5,\"overloaded\":5,\"p95\":400,\"max\":900}]}";

        K6Summary s = K6Summary.parse(log).orElseThrow();

        assertThat(s.waves()).isEqualTo(2);
        assertThat(s.perWave()).hasSize(2);
        assertThat(s.perWave().get(1).booked()).isEqualTo(90);
        assertThat(s.perWave().get(0).p95()).isEqualTo(2100);
        assertThat(s.perWave().get(1).vus()).isEqualTo(100);
    }

    @Test
    void anOlderLineIsOneWaveWithNoPerWaveList() {
        K6Summary s = K6Summary.parse("K6_SUMMARY {\"vus\":200,\"booked\":196}").orElseThrow();
        assertThat(s.waves()).isEqualTo(1);
        assertThat(s.perWave()).isEmpty();
    }

    @Test
    void anOlderLineHasNoOutcomes() {
        K6Summary s = K6Summary.parse("K6_SUMMARY {\"vus\":200,\"booked\":196}").orElseThrow();
        assertThat(s.hasOutcomes()).isFalse();
        assertThat(s.gaveUp()).isNull();
    }

    @Test
    void theLastSummaryWins() {
        String log = "K6_SUMMARY {\"vus\":1}\nK6_SUMMARY {\"vus\":2}\n";
        assertThat(K6Summary.parse(log).orElseThrow().vus()).isEqualTo(2);
    }

    @Test
    void noSummaryLineIsEmpty() {
        assertThat(K6Summary.parse("running\ndone\n")).isEmpty();
        assertThat(K6Summary.parse(null)).isEmpty();
    }

    @Test
    void malformedJsonIsEmpty() {
        assertThat(K6Summary.parse("K6_SUMMARY {not json")).isEmpty();
    }
}
