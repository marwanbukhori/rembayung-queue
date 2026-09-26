package dev.marwan.console.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ValidatorTest {

    final Validator validator = new Validator();

    Facts facts() {
        Facts f = new Facts();
        f.add("Prometheus", "Peak DB pool in use, booking-service-55bdc8bfd6-hlqdg", "5");   // F1
        f.add("k6", "Request latency p50 / p95 / max", "120 / 2100 / 3050 ms");             // F2
        f.add("Prometheus", "Peak p95 latency, booking-service", "2.5 s");                  // F3
        return f;
    }

    List<String> check(String text, String... ids) {
        return validator.problems(Report.sections(List.of(new Claim(text, List.of(ids))), List.of(), List.of(), List.of(), List.of()), facts());
    }

    @Test
    void digitsInsideAPodNameOrAUnitAreNotNumbers() {
        assertThat(check("booking-service-55bdc8bfd6-hlqdg saw 5xx and peaked at 5.", "F1")).isEmpty();
    }

    @Test
    void thousandsSeparatorsAndTrailingZerosMatch() {
        assertThat(check("p95 reached 2,100 ms.", "F2")).isEmpty();
        assertThat(check("Peak p95 was 2.50 s.", "F3")).isEmpty();
    }

    @Test
    void aNumberFromAnUncitedFactIsAProblem() {
        assertThat(check("p95 reached 2100 ms.", "F1")).singleElement().asString().contains("2100");
    }

    @Test
    void factIdsInTheTextAreNotNumbers() {
        assertThat(check("As F2 shows, p95 was 2100 ms.", "F2")).isEmpty();
    }

    @Test
    void numbersEndingASentenceOrCarryingAUnitAreChecked() {
        assertThat(check("The pool peaked at 9999.", "F1")).singleElement().asString().contains("9999");
        assertThat(check("p95 reached 2400ms.", "F2")).singleElement().asString().contains("2400");
        assertThat(check("It took 42s", "F2")).singleElement().asString().contains("42");
        assertThat(check("Latency grew 3.5x", "F2")).singleElement().asString().contains("3.5");
        assertThat(check("p95 reached 2100ms.", "F2")).isEmpty();
        assertThat(check("The pool peaked at 5.", "F1")).isEmpty();
    }

    @Test
    void withFunnelFactsTheSummaryAndCustomersAreRequired() {
        Facts f = facts();
        f.add("k6", "Arrived", "200");
        Report onlySummary = Report.sections(List.of(new Claim("Fine.", List.of("F1"))),
                List.of(), List.of(), List.of(), List.of());
        assertThat(validator.problems(onlySummary, f)).anyMatch(p -> p.contains("customers"));
        assertThat(validator.problems(onlySummary, facts())).isEmpty();
    }

    @Test
    void aClaimWithoutFactsIsAProblem() {
        assertThat(check("Everything was fine.")).singleElement().asString().contains("cites no facts");
    }

    @Test
    void anEmptyReportIsAProblem() {
        assertThat(validator.problems(Report.sections(List.of(), List.of(), List.of(), List.of(), List.of()), facts())).isNotEmpty();
    }
}
