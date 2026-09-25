package dev.marwan.console.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class AnalysisStoreTest {

    /** A ConfigMap API with resourceVersions: an update against a stale version conflicts. */
    static class FakePort implements AnalysisStore.ConfigMapPort {
        ConfigMap stored;
        int version;
        int conflictsToThrow;
        int writes;

        @Override
        public Optional<ConfigMap> get(String name) {
            return Optional.ofNullable(stored).map(c -> new ConfigMapBuilder(c).build());
        }

        @Override
        public void create(ConfigMap map) {
            writes++;
            map.getMetadata().setResourceVersion(String.valueOf(++version));
            stored = map;
        }

        @Override
        public void update(ConfigMap map) {
            writes++;
            if (conflictsToThrow > 0) {
                // Someone else wrote first: the stored object moves to a newer version.
                conflictsToThrow--;
                stored.getMetadata().setResourceVersion(String.valueOf(++version));
                throw new AnalysisStore.Conflict();
            }
            if (!String.valueOf(version).equals(map.getMetadata().getResourceVersion())) {
                throw new AnalysisStore.Conflict();
            }
            map.getMetadata().setResourceVersion(String.valueOf(++version));
            stored = map;
        }
    }

    static Analysis analysis(String job, Instant at, String bigValue) {
        return new Analysis(job, "drop", at, at.plusSeconds(60),
                List.of(new Fact("F1", "k6", "Bookings", bigValue == null ? "196 booked" : bigValue)),
                List.of(), new Report(List.of(new Claim("Fine.", List.of("F1"))), List.of(), List.of()),
                "scripted", "model", null, List.of(), at.plusSeconds(90), 1000);
    }

    @Test
    void createsTheConfigMapThenAddsToIt() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);

        store.put(analysis("load-a", Instant.parse("2026-09-25T10:00:00Z"), null));
        store.put(analysis("load-b", Instant.parse("2026-09-25T11:00:00Z"), null));

        assertThat(port.stored.getMetadata().getName()).isEqualTo("run-analyses");
        assertThat(store.jobs()).containsExactlyInAnyOrder("load-a", "load-b");
        assertThat(store.get("load-a")).isPresent();
        assertThat(store.list()).extracting(Analysis::job).containsExactly("load-b", "load-a");
    }

    @Test
    void keepsTheNewestTwelve() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);
        for (int i = 0; i < 15; i++) {
            store.put(analysis("load-" + i, Instant.parse("2026-09-25T10:00:00Z").plusSeconds(i * 60L), null));
        }
        assertThat(store.jobs()).hasSize(12).doesNotContain("load-0", "load-1", "load-2").contains("load-14");
    }

    @Test
    void aConflictRereadsAndRetriesOnce() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);
        store.put(analysis("load-a", Instant.parse("2026-09-25T10:00:00Z"), null));
        port.conflictsToThrow = 1;

        store.put(analysis("load-b", Instant.parse("2026-09-25T11:00:00Z"), null));

        assertThat(store.jobs()).contains("load-a", "load-b");
    }

    @Test
    void anOversizedEntryIsTrimmedUnderTheLimit() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);

        store.put(analysis("load-big", Instant.parse("2026-09-25T10:00:00Z"), "x".repeat(200_000)));

        assertThat(port.stored.getData().get("load-big").length()).isLessThanOrEqualTo(AnalysisStore.MAX_ENTRY);
        assertThat(store.get("load-big").orElseThrow().facts().get(0).value()).endsWith("…");
    }
}
