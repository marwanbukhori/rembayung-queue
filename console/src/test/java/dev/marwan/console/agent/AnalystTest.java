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
            {"went_well":[{"text":"No seat was oversold.","facts":["F6"]}],
             "caught":[{"text":"196 booked and 4 not clean.","facts":["F2"]}],
             "look_at":[{"text":"booking-service-a peaked at 5 connections.","facts":["F7"]}]}
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
        assertThat(a.report().caught().get(0).facts()).containsExactly("F2");
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
                .then("{\"went_well\":[],\"caught\":[{\"text\":\"17 bookings failed.\",\"facts\":[\"F2\"]}],\"look_at\":[]}")
                .then(GOOD_REPORT);

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.source()).isEqualTo("model");
        assertThat(a.problems()).isEmpty();
        List<Message> retry = model.seen.get(2);
        assertThat(retry.get(retry.size() - 1).content()).contains("17");
    }

    @Test
    void inventingTwiceFallsBackToTheFactsAlone() {
        String invented = "{\"went_well\":[],\"caught\":[{\"text\":\"17 bookings failed.\",\"facts\":[\"F2\"]}],\"look_at\":[]}";
        model.then("{\"done\":true}").then(invented).then(invented);

        Analysis a = analyst().analyse(WINDOW);

        assertThat(a.source()).isEqualTo("fallback");
        assertThat(a.problems()).isNotEmpty();
        assertThat(new Validator().problems(a.report(), new Facts(a.facts()))).isEmpty();
    }

    @Test
    void anUnknownFactIdIsAProblem() {
        model.then("{\"done\":true}")
                .then("{\"went_well\":[{\"text\":\"Fine.\",\"facts\":[\"F99\"]}],\"caught\":[],\"look_at\":[]}")
                .then("{\"went_well\":[{\"text\":\"Fine.\",\"facts\":[\"F99\"]}],\"caught\":[],\"look_at\":[]}");

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
        assertThat(a.report().wentWell()).isNotEmpty();
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
                .then("{\"went_well\":[{\"text\":{\"x\":1},\"facts\":[{\"id\":\"F1\"}]}],\"caught\":[],\"look_at\":[]}")
                .then("{\"went_well\":\"none\"}");
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
    void theFallbackReportCitesOnlyRealFactsAndPassesValidation() {
        Report r = Fallback.from(baseline);
        assertThat(new Validator().problems(r, baseline)).isEmpty();
        assertThat(r.wentWell()).anyMatch(c -> c.facts().contains("F6"));
        assertThat(r.caught()).anyMatch(c -> c.facts().contains("F9"));
    }
}
