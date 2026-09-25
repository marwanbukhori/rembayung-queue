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
