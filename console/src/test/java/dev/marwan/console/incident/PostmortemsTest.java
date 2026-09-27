package dev.marwan.console.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.marwan.console.agent.Message;
import dev.marwan.console.agent.Model;
import dev.marwan.console.agent.ModelUnavailable;
import dev.marwan.console.chaos.ChaosServiceTest;

class PostmortemsTest {

    final AtomicInteger calls = new AtomicInteger();
    final Model model = new Model() {
        @Override public String chat(List<Message> messages, Duration timeout) {
            calls.incrementAndGet();
            throw new ModelUnavailable("off in this test");
        }
        @Override public String name() { return "test"; }
    };
    final IncidentStore store = new IncidentStore(new ChaosServiceTest.Maps());
    final IncidentWatcher watcher = new IncidentWatcher(() -> null, store, Optional::empty, List::of, List::of,
            java.time.Clock.systemUTC(), i -> { });
    final Postmortems postmortems = new Postmortems(new PostmortemWriter(model), store, watcher);

    Incident closed(String id, Incident.Postmortem pm) {
        Incident i = new Incident();
        i.id = id;
        i.kind = "drill";
        i.fault = "squeeze-pool";
        i.status = "resolved";
        i.openedAt = Instant.parse("2026-09-27T02:00:00Z");
        i.resolvedAt = i.openedAt.plusSeconds(300);
        i.postmortem = pm;
        store.put(i);
        return i;
    }

    @Test
    void aClosedIncidentWithoutAPostmortemGetsOneOnTheNextSweep() {
        // Its postmortem was being written when the console restarted.
        closed("inc-1", null);
        postmortems.backfill();
        assertThat(store.get("inc-1").orElseThrow().postmortem).isNotNull();
        assertThat(store.get("inc-1").orElseThrow().postmortem.rootCause()).contains("squeeze-pool");
    }

    @Test
    void anIncidentThatHasItsPostmortemIsLeftAlone() {
        closed("inc-2", new Incident.Postmortem("s", "i", "c", "f", new ArrayList<>(), 1, 2, "model"));
        postmortems.backfill();
        assertThat(calls).hasValue(0);
        assertThat(store.get("inc-2").orElseThrow().postmortem.summary()).isEqualTo("s");
    }

    @Test
    void anOpenIncidentIsNotGivenAPostmortem() {
        Incident i = closed("inc-3", null);
        i.status = "open";
        i.resolvedAt = null;
        store.put(i);
        postmortems.backfill();
        assertThat(store.get("inc-3").orElseThrow().postmortem).isNull();
    }
}
