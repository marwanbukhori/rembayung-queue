package dev.marwan.console.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.EventBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marwan.console.metrics.RangeQuery;
import dev.marwan.console.metrics.Series;
import dev.marwan.console.state.DemoState;

class BaselineTest {

    static final Instant START = Instant.parse("2026-09-25T13:40:00Z");
    static final Instant END = Instant.parse("2026-09-25T13:41:30Z");
    static final RunWindow WINDOW = new RunWindow("load-rush-1", "rush-1", START, END);

    static final String NEW_SUMMARY = "K6_SUMMARY {\"vus\":200,\"iterations\":200,\"booked\":196,"
            + "\"rejected\":4,\"notClean\":4,\"p50\":120,\"p95\":2100,\"max\":3050,\"durationMs\":61000,"
            + "\"joined\":195,\"admitted\":90,\"soldOutAtJoin\":5,\"gaveUp\":105,\"refusedAfterAdmission\":0,"
            + "\"soldOut\":0,\"overloaded\":2,\"faults\":0,\"queueWaitP50\":44,\"queueWaitP95\":86,"
            + "\"queueWaitMax\":89,\"partySize\":2,\"patienceSeconds\":90}";
    static final String OLD_SUMMARY = "K6_SUMMARY {\"vus\":200,\"iterations\":200,\"booked\":196,"
            + "\"rejected\":4,\"notClean\":4,\"p50\":120,\"p95\":2100,\"max\":3050,\"durationMs\":61000}";

    FakeCluster cluster;
    Integer admitRate = 1;
    RangeQuery prometheus;
    int oversold;

    @BeforeEach
    void cluster() {
        cluster = new FakeCluster();
        cluster.pods.add(FakeCluster.loadPod("load-rush-1-abc", "load-rush-1"));
                cluster.logs.put("load-rush-1-abc", "running\n" + NEW_SUMMARY + "\n");
        cluster.pods.add(FakeCluster.pod("booking-service-a", "booking-service", 0));
        cluster.pods.add(FakeCluster.pod("booking-service-b", "booking-service", 1));
        cluster.logs.put("booking-service-a", String.join("\n",
                "2026-09-25T13:40:10.000Z {\"level\":\"WARN\",\"message\":\"HikariPool-1 - Connection is not available, request timed out after 2000ms\"}",
                "2026-09-25T13:40:11.000Z {\"level\":\"WARN\",\"message\":\"HikariPool-1 - Connection is not available, request timed out after 2000ms\"}",
                "2026-09-25T13:10:00.000Z {\"level\":\"WARN\",\"message\":\"HikariPool-1 - Connection is not available, before the run\"}",
                "2026-09-25T13:40:12.000Z {\"level\":\"INFO\",\"message\":\"booked for +60123456789\"}"));
        cluster.logs.put("booking-service-b", "");
        cluster.events.put("Deployment/booking-service", List.of(new EventBuilder().withType("Warning")
                .withReason("FailedScheduling").withMessage("0/3 nodes").withCount(2)
                .withLastTimestamp("2026-09-25T13:40:20Z").build()));
        prometheus = (promql, label, s, e, step) -> {
            if (promql.contains("hikaricp")) {
                return List.of(new Series("booking-service-a", List.of(new double[] {1, 2}, new double[] {2, 5})),
                        new Series("booking-service-b", List.of(new double[] {1, 0}, new double[] {2, 0})));
            }
            if (promql.contains("histogram_quantile")) {
                return List.of(new Series("booking-service", List.of(new double[] {1, 0.4}, new double[] {2, 2.1})));
            }
            if (promql.contains("status=~\"5..\"")) {
                return List.of(new Series("booking-service", List.of(new double[] {1, 0}, new double[] {2, 4})));
            }
            if (promql.contains("kube_horizontalpodautoscaler")) {
                return List.of(new Series("booking-service", List.of(new double[] {1, 2}, new double[] {2, 3})));
            }
            return List.of();
        };
        oversold = 0;
    }

    Baseline baseline() {
        return new Baseline(cluster, prometheus, 5, drop -> new DemoState(true, null, drop, 1, 250, 196, 54, oversold, 200, 90, 0,
                admitRate));
    }

    Map<String, String> byLabel(Facts facts) {
        return facts.all().stream().collect(java.util.stream.Collectors.toMap(Fact::label, Fact::value,
                (a, b) -> a + " | " + b, java.util.LinkedHashMap::new));
    }

    @Test
    void numbersFactsInOrderFromEverySource() {
        Facts facts = baseline().gather(WINDOW);
        Map<String, String> f = byLabel(facts);

        assertThat(facts.all().get(0).id()).isEqualTo("F1");
        assertThat(f.get("Bookings: clean, rejected, not clean")).isEqualTo("196 booked, 4 rejected, 4 not clean");
        assertThat(f.get("Seats oversold")).isEqualTo("0");
        assertThat(f.get("DB pool size per booking-service pod")).isEqualTo("5");
        assertThat(f.get("Peak DB pool in use, booking-service-a")).isEqualTo("5");
        assertThat(f.get("Peak DB pool in use, booking-service-b")).isEqualTo("0");
        assertThat(f.get("Peak p95 latency, booking-service")).isEqualTo("2100 ms");
        assertThat(f.get("Most 5xx responses in one minute, booking-service")).isEqualTo("4");
        assertThat(f.get("Peak replicas, booking-service")).isEqualTo("3 (from 2)");
        assertThat(f.get("Warning events in the window")).isEqualTo("FailedScheduling ×2 on Deployment/booking-service");
        assertThat(f.get("Pod restarts")).isEqualTo("booking-service-b: 1");
    }

    @Test
    void turnsTheCustomerCountsIntoFunnelFacts() {
        Map<String, String> f = byLabel(baseline().gather(WINDOW));

        assertThat(f.get("Arrived")).isEqualTo("200");
        assertThat(f.get("Joined the queue")).isEqualTo("195");
        assertThat(f.get("Admitted")).isEqualTo("90");
        assertThat(f.get("Booked")).isEqualTo("196");
        assertThat(f.get("Seats taken by this run")).isEqualTo("392");
        assertThat(f.get("Gave up waiting (403)")).isEqualTo("105");
        assertThat(f.get("Sold out at the queue (409)")).isEqualTo("5");
        assertThat(f.get("Overloaded (503)")).isEqualTo("2");
        assertThat(f).doesNotContainKey("Sold out at booking (409)");
        assertThat(f.get("Admit rate")).isEqualTo("1 per second");
        assertThat(f.get("Queue patience")).isEqualTo("90 s");
        assertThat(f.get("Party size")).isEqualTo("2");
        assertThat(f.get("Queue wait p50 / p95 / max")).isEqualTo("44 / 86 / 89 s");
        assertThat(f.get("Still waiting")).isEqualTo("0");
        assertThat(f.get("Tickets issued")).isEqualTo("200");
        assertThat(f.get("Seats taken / capacity")).isEqualTo("196 / 250");
    }

    @Test
    void customersWithNoOutcomeAreCountedAsNotFinished() {
        // 200 arrived, but the outcomes account for 196 + 105 + 5 + 2 = 308 in NEW_SUMMARY; use a line that falls short.
        cluster.logs.put("load-rush-1-abc", "running\nK6_SUMMARY {\"vus\":200,\"booked\":80,\"joined\":190,"
                + "\"admitted\":85,\"soldOutAtJoin\":5,\"gaveUp\":100,\"refusedAfterAdmission\":0,\"soldOut\":0,"
                + "\"overloaded\":2,\"faults\":3,\"faultsAtJoin\":1,\"faultsAtBooking\":2,\"queueWaitP50\":1,"
                + "\"queueWaitP95\":2,\"queueWaitMax\":3,\"partySize\":2,\"patienceSeconds\":90}\n");
        Map<String, String> f = byLabel(baseline().gather(WINDOW));
        assertThat(f.get("Did not finish")).isEqualTo("10");
        assertThat(f.get("Faults at the queue")).isEqualTo("1");
        assertThat(f.get("Faults at booking")).isEqualTo("2");
        assertThat(f).doesNotContainKey("Other faults");
    }

    @Test
    void theAdmissionsPossibleWithinPatienceAreAFactTheModelCanCite() {
        admitRate = 8;
        assertThat(byLabel(baseline().gather(WINDOW)).get("Admissions possible within patience")).isEqualTo("720");
    }

    static final String TWO_WAVES = "K6_SUMMARY {\"vus\":200,\"booked\":150,\"joined\":200,\"admitted\":165,"
            + "\"soldOutAtJoin\":0,\"gaveUp\":35,\"refusedAfterAdmission\":0,\"soldOut\":0,\"overloaded\":15,"
            + "\"faults\":0,\"faultsAtJoin\":0,\"faultsAtBooking\":0,\"queueWaitP50\":1,\"queueWaitP95\":2,"
            + "\"queueWaitMax\":3,\"partySize\":2,\"patienceSeconds\":90,\"waves\":2,\"perWave\":["
            + "{\"wave\":1,\"vus\":100,\"joined\":100,\"admitted\":70,\"booked\":60,\"soldOutAtJoin\":0,\"gaveUp\":30,"
            + "\"refusedAfterAdmission\":0,\"soldOut\":0,\"overloaded\":10,\"faultsAtJoin\":0,\"faultsAtBooking\":0,"
            + "\"p95\":2100,\"max\":4000},"
            + "{\"wave\":2,\"vus\":100,\"joined\":100,\"admitted\":95,\"booked\":90,\"soldOutAtJoin\":0,\"gaveUp\":5,"
            + "\"refusedAfterAdmission\":0,\"soldOut\":0,\"overloaded\":5,\"faultsAtJoin\":0,\"faultsAtBooking\":0,"
            + "\"p95\":400,\"max\":900}]}";
    static final RunWindow TWO_WAVE_WINDOW = new RunWindow("load-rush-1", "rush-1", START, START.plusSeconds(330), 2, 180);

    void twoWaveCluster() {
        cluster.logs.put("load-rush-1-abc", "running\n" + TWO_WAVES + "\n");
        long t0 = START.getEpochSecond();
        prometheus = (promql, label, s, e, step) -> promql.contains("kube_horizontalpodautoscaler")
                ? List.of(new Series("queue-gate", List.of(new double[] {t0, 2}, new double[] {t0 + 60, 2},
                        new double[] {t0 + 75, 6}, new double[] {t0 + 180, 6}, new double[] {t0 + 300, 4})))
                : List.of();
    }

    @Test
    void aTwoWaveRunGetsEachWavesFacts() {
        twoWaveCluster();
        Map<String, String> f = byLabel(baseline().gather(TWO_WAVE_WINDOW));

        assertThat(f.get("Wave 1 · Arrived")).isEqualTo("100");
        assertThat(f.get("Wave 2 · Booked")).isEqualTo("90");
        assertThat(f.get("Wave 1 · Gave up waiting (403)")).isEqualTo("30");
        assertThat(f.get("Wave 2 · Overloaded (503)")).isEqualTo("5");
        assertThat(f.get("Wave 1 · Latency p95 / max")).isEqualTo("2100 / 4000 ms");
        assertThat(f.get("Wave 1 · Ready pods at start, queue-gate")).isEqualTo("2");
        assertThat(f.get("Wave 2 · Ready pods at start, queue-gate")).isEqualTo("6");
        assertThat(f.get("Scaling, queue-gate")).isEqualTo("2 → 6 at +1m15s, 6 → 4 at +5m0s");
    }

    @Test
    void aTwoWaveRunCutOffAfterWaveOneGetsOnlyWaveOneFacts() {
        cluster.logs.put("load-rush-1-abc", "running\n" + TWO_WAVES.replaceAll(",\\{\"wave\":2.*\\]", "]") + "\n");
        Map<String, String> f = byLabel(baseline().gather(TWO_WAVE_WINDOW));
        assertThat(f).containsKey("Wave 1 · Arrived").doesNotContainKey("Wave 2 · Arrived");
    }

    @Test
    void aCutOffRunHasNoWaveTwoPodsFactToCompareAgainst() {
        twoWaveCluster();
        cluster.logs.put("load-rush-1-abc", "running\n" + TWO_WAVES.replaceAll(",\\{\"wave\":2.*\\]", "]") + "\n");
        Map<String, String> f = byLabel(baseline().gather(TWO_WAVE_WINDOW));
        assertThat(f).doesNotContainKey("Wave 2 · Ready pods at start, queue-gate");
    }

    @Test
    void aTwoWaveRunChecksBothSittingsForOversold() {
        twoWaveCluster();
        RunWindow w = new RunWindow("load-rush-1", "rush-1", START, START.plusSeconds(330), 2, 180, "rush-2");
        Baseline both = new Baseline(cluster, prometheus, 5, drop -> new DemoState(true, null, drop, 1, 250, 196, 54,
                drop.equals("rush-2") ? 1 : 0, 200, 90, 0, admitRate));

        Map<String, String> f = byLabel(both.gather(w));

        assertThat(f.get("Wave 1 · Seats oversold")).isEqualTo("0");
        assertThat(f.get("Wave 2 · Seats oversold")).isEqualTo("1");
        assertThat(f.get("Seats oversold")).isEqualTo("1");
        assertThat(f.get("Wave 2 · Seats taken / capacity")).isEqualTo("196 / 250");
    }

    @Test
    void podsHeldBackByTheCpuBudgetAreAFact() {
        cluster.events.put("Deployment/queue-gate", List.of(new EventBuilder().withType("Warning")
                .withReason("FailedCreate").withMessage("pods \"queue-gate-x\" is forbidden: exceeded quota: compute-deploy")
                .withLastTimestamp("2026-09-25T13:40:30Z").build()));
        Map<String, String> f = byLabel(baseline().gather(WINDOW));
        assertThat(f.get("Pods waiting for CPU during the run")).contains("exceeded quota");
    }

    @Test
    void aOneWaveRunHasNoWaveFactsAndASteadyAutoscalerNoScalingFact() {
        prometheus = (promql, label, s, e, step) -> promql.contains("kube_horizontalpodautoscaler")
                ? List.of(new Series("queue-gate", List.of(new double[] {START.getEpochSecond(), 2},
                        new double[] {START.getEpochSecond() + 60, 2})))
                : List.of();
        Map<String, String> f = byLabel(baseline().gather(WINDOW));
        assertThat(f.keySet()).noneMatch(k -> k.startsWith("Wave "));
        assertThat(f).doesNotContainKey("Scaling, queue-gate");
        assertThat(f.get("Pods waiting for CPU during the run")).isEqualTo("none");
    }

    @Test
    void anOldK6LineGivesOneOutcomesUnavailableFact() {
        cluster.logs.put("load-rush-1-abc", "running\n" + OLD_SUMMARY + "\n");
        Map<String, String> f = byLabel(baseline().gather(WINDOW));
        assertThat(f.get("Customer outcomes")).startsWith("unavailable");
        assertThat(f).doesNotContainKey("Arrived");
    }

    @Test
    void aGateWithoutAnAdmitRateSaysSo() {
        admitRate = null;
        assertThat(byLabel(baseline().gather(WINDOW)).get("Admit rate"))
                .isEqualTo("unavailable: the gate did not report it");
    }

    @Test
    void countsPoolTimeoutsOnlyInsideTheWindow() {
        Map<String, String> f = byLabel(baseline().gather(WINDOW));
        assertThat(f.get("Pool timeouts in the window, booking-service-a")).isEqualTo("2");
        assertThat(f.get("Pool timeouts in the window, booking-service-b")).isEqualTo("0");
    }

    @Test
    void aSourceThatFailsBecomesAFactRatherThanAnError() {
        cluster.logs.remove("load-rush-1-abc");
        prometheus = (q, l, s, e, st) -> { throw new IllegalStateException("Prometheus answered HTTP 503"); };

        Map<String, String> f = byLabel(baseline().gather(WINDOW));

        assertThat(f.get("k6 summary")).startsWith("unavailable");
        assertThat(f.get("Prometheus")).isEqualTo("unavailable: Prometheus answered HTTP 503");
        assertThat(f.get("Seats oversold")).isEqualTo("0");
    }

    @Test
    void anOversoldSeatIsAFactToo() {
        oversold = 2;
        assertThat(byLabel(baseline().gather(WINDOW)).get("Seats oversold")).isEqualTo("2");
    }

    @Test
    void factsNeverCarryAPhoneNumber() {
        Facts facts = baseline().gather(WINDOW);
        assertThat(facts.all()).allSatisfy(fact -> assertThat(fact.value()).doesNotContain("123456789"));
    }
}
