package dev.marwan.console.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marwan.console.agent.AnalystTest;
import dev.marwan.console.agent.ModelUnavailable;
import dev.marwan.console.agent.ToolCaller;
import dev.marwan.console.chaos.ChaosService;
import dev.marwan.console.chaos.ChaosServiceTest;
import dev.marwan.console.slo.SloReading;

class IncidentCommanderTest {

    IncidentWatcherTest.MovingClock clock;
    IncidentStore store;
    IncidentWatcher watcher;
    AnalystTest.Scripted model;
    int toolCalls;
    IncidentCommander commander;

    static final String DIAGNOSIS = """
            {"cause":"booking-service's pool is saturated","confidence":"high",
             "claims":[{"text":"Booking success fell to 90% with p95 at 3 s.","facts":["F1","F2"]}],
             "proposal":{"action":"scale-booking","target":"booking-service","replicas":3,
                         "reason":"More pods, more connections.","facts":["F1"]}}
            """;

    @BeforeEach
    void setUp() {
        clock = new IncidentWatcherTest.MovingClock();
        store = new IncidentStore(new ChaosServiceTest.Maps());
        SloReading breach = new SloReading(clock.now, true, null, true, 0.9, 3.0);
        watcher = new IncidentWatcher(() -> breach, store, Optional::empty, List::of, List::of, clock, i -> { });
        watcher.drillStarted(new ChaosService.ActiveFault("squeeze-pool", clock.now, clock.now.plusSeconds(120)));
        model = new AnalystTest.Scripted();
        ToolCaller tools = (tool, args, w, facts) -> {
            toolCalls++;
            return new ToolCaller.Call(facts.add("tool: " + tool, tool, "booking-service-a: pool 1 of 1"), "mcp");
        };
        commander = new IncidentCommander(tools, model, () -> breach, Optional::empty, store, watcher, clock);
    }

    Incident open() {
        return store.open().orElseThrow();
    }

    @Test
    void aCycleDiagnosesThroughMcpAndFilesAPendingProposal() {
        model.then("{\"call\":\"metric\",\"args\":{\"chart\":\"pool\"},\"why\":\"check the pool\"}")
                .then("{\"done\":true}").then(DIAGNOSIS);

        commander.cycle();

        Incident i = open();
        assertThat(toolCalls).isEqualTo(1);
        assertThat(i.diagnoses).singleElement().satisfies(d -> {
            assertThat(d.cause()).contains("pool");
            assertThat(d.confidence()).isEqualTo("high");
        });
        assertThat(i.detectedAt).isNotNull();
        assertThat(i.proposals).singleElement().satisfies(p -> {
            assertThat(p.action()).isEqualTo("scale-booking");
            assertThat(p.replicas()).isEqualTo(3);
            assertThat(p.status()).isEqualTo("pending");
        });
        assertThat(i.timeline).anyMatch(e -> e.source().equals("agent") && e.text().contains("proposes"));
    }

    @Test
    void anActionOffTheMenuIsNeverProposed() {
        model.then("{\"done\":true}").then(DIAGNOSIS.replace("scale-booking", "delete-namespace"));
        commander.cycle();
        assertThat(open().proposals).isEmpty();
    }

    @Test
    void lowConfidenceProposesNothing() {
        model.then("{\"done\":true}").then(DIAGNOSIS.replace("\"high\"", "\"low\""));
        commander.cycle();
        assertThat(open().proposals).isEmpty();
        assertThat(open().diagnoses).hasSize(1);
    }

    @Test
    void anInventedNumberGetsOneRetryThenAnHonestNotYet() {
        String invented = DIAGNOSIS.replace("90%", "42%");
        model.then("{\"done\":true}").then(invented).then(invented);
        commander.cycle();
        Incident i = open();
        assertThat(i.diagnoses.get(0).cause()).contains("could not determine");
        assertThat(i.proposals).isEmpty();
        assertThat(i.detectedAt).isNull();
    }

    @Test
    void aModelThatIsDownDoesNotStopTheIncident() {
        model.then(new ModelUnavailable("the model timed out after 60 s"));
        commander.cycle();
        assertThat(open().diagnoses.get(0).cause()).contains("could not determine");
    }

    @Test
    void oneOpenProposalAtATime() {
        model.then("{\"done\":true}").then(DIAGNOSIS).then("{\"done\":true}").then(DIAGNOSIS);
        commander.cycle();
        commander.cycle();
        assertThat(open().proposals).hasSize(1);
    }

    @Test
    void afterTenCyclesTheIncidentGoesToAPerson() {
        for (int c = 0; c < 11; c++) {
            model.then("{\"done\":true}").then(DIAGNOSIS.replace("\"high\"", "\"low\""));
            commander.cycle();
        }
        Incident i = store.list().get(0);
        assertThat(i.status).isEqualTo("unresolved");
        assertThat(i.cycles).isEqualTo(10);
    }

    @Test
    void thePostmortemsTimesComeFromTheTimelineNotTheModel() {
        Incident i = open();
        i.detectedAt = i.openedAt.plusSeconds(40);
        i.resolvedAt = i.openedAt.plusSeconds(190);
        i.status = "resolved";
        model.then(new ModelUnavailable("down"));
        Incident.Postmortem pm = new PostmortemWriter(model).write(i);
        assertThat(pm.timeToDetectSeconds()).isEqualTo(40);
        assertThat(pm.timeToRecoverSeconds()).isEqualTo(150);
        assertThat(pm.source()).isEqualTo("fallback");
        assertThat(pm.summary()).contains("squeeze-pool");
    }

    @Test
    void aModelWrittenPostmortemIsValidated() {
        Incident i = open();
        i.detectedAt = i.openedAt.plusSeconds(40);
        i.resolvedAt = i.openedAt.plusSeconds(190);
        i.status = "resolved";
        model.then("""
                {"summary":"A drill squeezed the pool to 1 connection.","impact":"Bookings failed for 3 minutes.",
                 "root_cause":"The pool was cut.","fix":"The drill ended.","follow_ups":["Alert on pool pending."]}
                """);
        Incident.Postmortem pm = new PostmortemWriter(model).write(i);
        assertThat(pm.source()).isEqualTo("fallback");   // "3 minutes" and "1 connection" are in no fact
    }
}
