package dev.marwan.console.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalystTest {

    static final RunWindow WINDOW = BaselineTest.WINDOW;

    /** Answers in order; an exception in the queue is thrown instead. */
    static class Scripted implements Model {
        final Deque<Object> answers = new ArrayDeque<>();
        final List<List<Message>> seen = new ArrayList<>();

        Scripted then(Object answer) {
            answers.add(answer);
            return this;
        }

        @Override
        public String chat(List<Message> messages, Duration timeout) {
            seen.add(List.copyOf(messages));
            Object next = answers.isEmpty() ? "{\"done\":true}" : answers.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (String) next;
        }

        @Override
        public String name() {
            return "scripted";
        }
    }

    static final String GOOD_REPORT = """
            {"summary":[{"text":"No seat was oversold.","facts":["F6"]}],
             "customers":[{"text":"196 booked and 4 not clean.","facts":["F2"]}],
             "capacity":[{"text":"booking-service-a peaked at 5 connections.","facts":["F7"]}],
             "errors":[{"text":"At most 4 5xx responses in one minute.","facts":["F5"]}],
             "look_at":[]}
            """;

    Facts baseline;
    Scripted model;

    @BeforeEach
    void setUp() {
        baseline = new Facts();
        baseline.add("k6", "Customers arriving at once", "200");                       // F1
        baseline.add("k6", "Bookings: clean, rejected, not clean", "196 booked, 4 rejected, 4 not clean"); // F2
        baseline.add("k6", "Request latency p50 / p95 / max", "120 / 2100 / 3050 ms"); // F3
        baseline.add("k6", "Run duration", "61 s");                                    // F4
        baseline.add("Prometheus", "Most 5xx responses in one minute, booking-service", "4"); // F5
        baseline.add("invariant", "Seats oversold", "0");                             // F6
        baseline.add("Prometheus", "Peak DB pool in use, booking-service-a", "5");     // F7
        baseline.add("Prometheus", "Peak DB pool in use, booking-service-b", "0");     // F8
        baseline.add("logs", "Pool timeouts in the window, booking-service-a", "2");   // F9
        model = new Scripted();
    }

    Analyst analyst(Clock clock) {
        FakeCluster cluster = new FakeCluster();
        cluster.pods.add(FakeCluster.pod("booking-service-a", "booking-service", 0));
        cluster.logs.put("booking-service-a",
                "2026-09-25T13:40:10.000Z {\"level\":\"WARN\",\"message\":\"Connection is not available\"}");
        Tools tools = new Tools(cluster, (q, l, s, e, st) -> List.of());
        return new Analyst(w -> copy(baseline), tools, model, clock);
    }

    Analyst analyst() {
        return analyst(Clock.systemUTC());
    }

    static Facts copy(Facts f) {
        return new Facts(f.all());
    }

    @Test
    void aCleanRunInvestigatesThenReports() {
        model.then("{\"call\":\"pod_logs\",\"args\":{\"pod\":\"booking-service-a\",\"level\":\"WARN\"},"
                        + "\"why\":\"pool timeouts on pod a\"}")
                .then("{\"done\":true}")
                .then(GOOD_REPORT);

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.source()).isEqualTo("model");
        assertThat(a.trail()).hasSize(1);
        assertThat(a.trail().get(0).factId()).isEqualTo("F10");
        assertThat(a.trail().get(0).why()).isEqualTo("pool timeouts on pod a");
        assertThat(a.facts()).hasSize(10);
        assertThat(a.report().customers().get(0).facts()).containsExactly("F2");
        assertThat(a.problems()).isEmpty();
    }

    @Test
    void aModelThatNeverStopsIsCutOffAtFiveCalls() {
        // Five calls use the budget; the next thing the loop asks for is the report,
        // so a sixth call is never requested.
        for (int i = 0; i < 5; i++) {
            model.then("{\"call\":\"pod_status\",\"args\":{\"pod\":\"booking-service-a\"},\"why\":\"again\"}");
        }
        model.then(GOOD_REPORT);

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.trail()).hasSize(5);
        assertThat(a.source()).isEqualTo("model");
    }

    @Test
    void anInventedNumberIsSentBackOnceAndCorrected() {
        model.then("{\"done\":true}")
                .then("{\"summary\":[{\"text\":\"No seat was oversold.\",\"facts\":[\"F6\"]}],\"customers\":[{\"text\":\"17 bookings failed.\",\"facts\":[\"F2\"]}]}")
                .then(GOOD_REPORT);

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.source()).isEqualTo("model");
        assertThat(a.problems()).isEmpty();
        List<Message> retry = model.seen.get(2);
        assertThat(retry.get(retry.size() - 1).content()).contains("17");
    }

    @Test
    void inventingTwiceFallsBackToTheFactsAlone() {
        String invented = "{\"summary\":[{\"text\":\"No seat was oversold.\",\"facts\":[\"F6\"]}],\"customers\":[{\"text\":\"17 bookings failed.\",\"facts\":[\"F2\"]}]}";
        model.then("{\"done\":true}").then(invented).then(invented);

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.source()).isEqualTo("fallback");
        assertThat(a.problems()).isNotEmpty();
        assertThat(new Validator().problems(a.report(), new Facts(a.facts()))).isEmpty();
    }

    @Test
    void anUnknownFactIdIsAProblem() {
        model.then("{\"done\":true}")
                .then("{\"summary\":[{\"text\":\"Fine.\",\"facts\":[\"F99\"]}]}")
                .then("{\"summary\":[{\"text\":\"Fine.\",\"facts\":[\"F99\"]}]}");

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.source()).isEqualTo("fallback");
        assertThat(a.problems()).anyMatch(p -> p.contains("F99"));
    }

    @Test
    void aModelThatTimesOutStillGivesTheRunAReport() {
        model.then(new ModelUnavailable("the model timed out after 60 s"));

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.source()).isEqualTo("fallback");
        assertThat(a.note()).contains("timed out");
        assertThat(a.report().summary()).isNotEmpty();
    }

    @Test
    void proseInsteadOfJsonFallsBack() {
        model.then("Sure! Here is my analysis of the run.").then("Still prose.").then("And more prose.");

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.source()).isEqualTo("fallback");
    }

    @Test
    void oddlyShapedJsonFallsBackRatherThanThrowing() {
        model.then("{\"call\":{\"name\":\"pod_logs\"},\"args\":{}}")
                .then("{\"summary\":[{\"text\":{\"x\":1},\"facts\":[{\"id\":\"F1\"}]}]}")
                .then("{\"summary\":\"none\"}");
        Analysis a = analyst().analyse(WINDOW);
        assertThat(a.report()).isNotNull();
        assertThat(a.source()).isEqualTo("fallback");
    }

    @Test
    void anUnexpectedFailureStillEndsInAReport() {
        model.then(new IllegalStateException("something nobody planned for"));
        Analysis a = analyst().analyse(WINDOW);
        assertThat(a.source()).isEqualTo("fallback");
        assertThat(a.note()).contains("something nobody planned for");
    }

    @Test
    void theTrailIsMasked() {
        model.then("{\"call\":\"pod_status\",\"args\":{\"pod\":\"booking-service-a\"},\"why\":\"customer +60123456789\"}")
                .then("{\"done\":true}").then(GOOD_REPORT);
        Analysis a = analyst().analyse(WINDOW);
        assertThat(a.trail().get(0).why()).doesNotContain("123456789");
    }

    @Test
    void anEmptyPlaceholderItemIsDroppedNotHeldAgainstTheReport() {
        model.then("{\"done\":true}").then("""
                {"summary":[{"text":"No seat was oversold.","facts":["F6"]}],
                 "customers":[{"text":"196 booked and 4 not clean.","facts":["F2"]}, {}],
                 "look_at":[{"text":"","facts":[]}]}
                """);
        Analysis a = analyst().analyse(WINDOW);
        assertThat(a.source()).isEqualTo("model");
        assertThat(a.report().customers()).hasSize(1);
        assertThat(a.report().lookAt()).isEmpty();
    }

    @Test
    void thePromptForbidsComputedNumbersAndDoesNotAskForThem() {
        assertThat(Analyst.SYSTEM).doesNotContain("N x patience");
        assertThat(Analyst.REPORT).contains("do not compute new numbers");
    }

    @Test
    void jsonInsideAFenceIsAccepted() {
        model.then("```json\n{\"done\":true}\n```").then("```json\n" + GOOD_REPORT + "\n```");
        assertThat(analyst().analyse(WINDOW).source()).isEqualTo("model");
    }

    @Test
    void theRunBudgetGoesStraightToTheReport() {
        // A clock that jumps two minutes per read: the budget is spent after the first call.
        Clock jumping = new Clock() {
            Instant now = Instant.parse("2026-09-25T14:00:00Z");
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { now = now.plusSeconds(120); return now; }
        };
        for (int i = 0; i < 5; i++) {
            model.then("{\"call\":\"pod_status\",\"args\":{\"pod\":\"booking-service-a\"},\"why\":\"x\"}");
        }

        Analysis a = analyst(jumping).analyse(WINDOW);

        assertThat(a.trail().size()).isLessThan(5);
        assertThat(a.report()).isNotNull();
    }

    @Test
    void theFallbackFilesUncleanBookingsAsCaughtNotAsWentWell() {
        Report r = Fallback.from(baseline);   // F2 says 4 not clean
        assertThat(r.errors()).anyMatch(c -> c.facts().contains("F2") && c.text().contains("not clean"));

        Facts clean = new Facts();
        clean.add("k6", "Bookings: clean, rejected, not clean", "200 booked, 0 rejected, 0 not clean");
        assertThat(Fallback.from(clean).errors()).noneMatch(c -> c.text().contains("not clean"));
        assertThat(Fallback.from(clean).summary()).anyMatch(c -> c.facts().contains("F1"));
    }

    @Test
    void theFallbackReportCitesOnlyRealFactsAndPassesValidation() {
        Report r = Fallback.from(baseline);
        assertThat(new Validator().problems(r, baseline)).isEmpty();
        assertThat(r.summary()).anyMatch(c -> c.facts().contains("F6"));
        assertThat(r.errors()).anyMatch(c -> c.facts().contains("F9"));
    }

    /** The baseline plus the funnel facts Task 3 adds, numbered after F9. */
    Facts withFunnel() {
        Facts f = copy(baseline);
        f.add("k6", "Arrived", "200");                       // F10
        f.add("k6", "Joined the queue", "195");              // F11
        f.add("k6", "Admitted", "90");                       // F12
        f.add("k6", "Booked", "88");                         // F13
        f.add("k6", "Seats taken by this run", "176");       // F14
        f.add("k6", "Gave up waiting (403)", "105");         // F15
        f.add("k6", "Sold out at the queue (409)", "5");     // F16
        f.add("k6", "Overloaded (503)", "2");                // F17
        f.add("queue-gate", "Admit rate", "1 per second");   // F18
        return f;
    }

    @Test
    void theFallbackTellsTheCustomerStoryFromTheFunnel() {
        Facts f = withFunnel();
        Report r = Fallback.from(f);
        assertThat(r.summary()).singleElement().satisfies(c -> assertThat(c.facts()).contains("F13", "F10"));
        assertThat(r.customers()).anyMatch(c -> c.facts().contains("F15"))
                .anyMatch(c -> c.facts().contains("F16")).anyMatch(c -> c.facts().contains("F17"));
        assertThat(new Validator().problems(r, f)).isEmpty();
    }

    @Test
    void aGaveUpMajorityGetsALookAtAboutTheAdmitRate() {
        Report r = Fallback.from(withFunnel());
        assertThat(r.lookAt()).anyMatch(c -> c.facts().contains("F15") && c.text().contains("admit rate"));
    }

    @Test
    void aReportWithoutTheCustomersSectionIsRetriedThenFallsBackWhenFunnelFactsExist() {
        Facts funnel = withFunnel();
        baseline = funnel;
        String noCustomers = "{\"summary\":[{\"text\":\"No seat was oversold.\",\"facts\":[\"F6\"]}]}";
        model.then("{\"done\":true}").then(noCustomers).then(noCustomers);

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.source()).isEqualTo("fallback");
        assertThat(a.problems()).anyMatch(p -> p.contains("customers"));
    }
}
