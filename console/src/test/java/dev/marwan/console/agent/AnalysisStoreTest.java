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

        boolean createRace;

        @Override
        public void create(ConfigMap map) {
            writes++;
            if (createRace) {
                // Another console pod created it a moment earlier.
                createRace = false;
                stored = new ConfigMapBuilder(map).build();
                stored.getMetadata().setResourceVersion(String.valueOf(++version));
                stored.setData(new java.util.LinkedHashMap<>(java.util.Map.of()));
                throw new AnalysisStore.Conflict();
            }
            map.getMetadata().setResourceVersion(String.valueOf(++version));
            stored = map;
        }

        ConfigMap lastUpdate;

        @Override
        public void update(ConfigMap map) {
            writes++;
            lastUpdate = map;
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
                List.of(), Report.sections(List.of(new Claim("Fine.", List.of("F1"))), List.of(), List.of(), List.of(), List.of()),
                "scripted", "model", null, List.of(), at.plusSeconds(90), 1000);
    }

    @Test
    void createsTheConfigMapThenAddsToIt() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);

        store.put(analysis("load-a", Instant.parse("2026-09-25T10:00:00Z"), null));
        store.put(analysis("load-b", Instant.parse("2026-09-25T11:00:00Z"), null));

        assertThat(port.stored.getMetadata().getName()).isEqualTo("run-analyses");
        assertThat(store.list()).extracting(Analysis::job).containsExactlyInAnyOrder("load-a", "load-b");
        assertThat(store.get("load-a")).isPresent();
        assertThat(store.list()).extracting(Analysis::job).containsExactly("load-b", "load-a");
    }

    /** fabric8's serialiser fails on managedFields as read back from the API; an update must not carry them. */
    @Test
    void anUpdateSendsACleanObjectWithoutTheServersBookkeeping() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);
        store.put(analysis("load-a", Instant.parse("2026-09-25T10:00:00Z"), null));
        port.stored.getMetadata().setManagedFields(List.of(new io.fabric8.kubernetes.api.model.ManagedFieldsEntry()));

        store.put(analysis("load-b", Instant.parse("2026-09-25T11:00:00Z"), null));

        assertThat(port.lastUpdate.getMetadata().getManagedFields()).isNullOrEmpty();
        assertThat(port.lastUpdate.getMetadata().getResourceVersion()).isNotNull();
        assertThat(port.lastUpdate.getMetadata().getLabels()).containsEntry("app.kubernetes.io/component", "run-analysis");
    }

    @Test
    void aReportStoredBeforeSectionsIsStillRead() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);
        store.put(analysis("load-old", Instant.parse("2026-09-25T10:00:00Z"), null));
        String key = port.stored.getData().keySet().iterator().next();
        String legacy = port.stored.getData().get(key).replaceAll("\"report\":\\{.*?\\},\"model\"",
                "\"report\":{\"wentWell\":[{\"text\":\"Fine.\",\"facts\":[\"F1\"]}],\"caught\":[],\"lookAt\":[]},\"model\"");
        port.stored.getData().put(key, legacy);

        Report r = store.get(key).orElseThrow().report();

        assertThat(r.wentWell()).hasSize(1);
        assertThat(r.summary()).isEmpty();
        assertThat(r.beforeAfter()).isEmpty();
    }

    @Test
    void keepsTheNewestTwelve() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);
        for (int i = 0; i < 15; i++) {
            store.put(analysis("load-" + i, Instant.parse("2026-09-25T10:00:00Z").plusSeconds(i * 60L), null));
        }
        assertThat(store.list()).hasSize(12).extracting(Analysis::job).doesNotContain("load-0", "load-1", "load-2").contains("load-14");
    }

    @Test
    void aConflictRereadsAndRetriesOnce() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);
        store.put(analysis("load-a", Instant.parse("2026-09-25T10:00:00Z"), null));
        port.conflictsToThrow = 1;

        store.put(analysis("load-b", Instant.parse("2026-09-25T11:00:00Z"), null));

        assertThat(store.list()).extracting(Analysis::job).contains("load-a", "load-b");
    }

    @Test
    void twoRunsWithTheSameJobNameAreKeptApart() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);
        store.put(analysis("load-d1", Instant.parse("2026-09-25T10:00:00Z"), "first"));
        store.put(analysis("load-d1", Instant.parse("2026-09-25T11:00:00Z"), "second"));

        assertThat(store.keys()).hasSize(2);
        assertThat(store.get("load-d1").orElseThrow().facts().get(0).value()).isEqualTo("second");
        assertThat(store.get("load-d1-" + Instant.parse("2026-09-25T10:00:00Z").getEpochSecond())
                .orElseThrow().facts().get(0).value()).isEqualTo("first");
    }

    @Test
    void aCreateRaceWithAnotherPodStillStores() {
        FakePort port = new FakePort();
        port.createRace = true;
        AnalysisStore store = new AnalysisStore(port);
        store.put(analysis("load-a", Instant.parse("2026-09-25T10:00:00Z"), null));
        assertThat(store.get("load-a")).isPresent();
    }

    @Test
    void theListIsOrderedByWhenTheRunEndedNotWhenItWasAnalysed() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);
        Analysis older = analysis("load-old", Instant.parse("2026-09-25T10:00:00Z"), null);
        Analysis newer = analysis("load-new", Instant.parse("2026-09-25T11:00:00Z"), null);
        store.put(newer);
        store.put(new Analysis(older.job(), older.dropId(), older.start(), older.end(), older.facts(), older.trail(),
                older.report(), older.model(), older.source(), older.note(), older.problems(),
                Instant.parse("2026-09-25T12:00:00Z"), 1));   // re-analysed later
        assertThat(store.list()).extracting(Analysis::job).containsExactly("load-new", "load-old");
    }

    @Test
    void anOversizedEntryIsTrimmedUnderTheLimit() {
        FakePort port = new FakePort();
        AnalysisStore store = new AnalysisStore(port);

        store.put(analysis("load-big", Instant.parse("2026-09-25T10:00:00Z"), "x".repeat(200_000)));

        assertThat(port.stored.getData().get("load-big-" + Instant.parse("2026-09-25T10:00:00Z").getEpochSecond()).length()).isLessThanOrEqualTo(AnalysisStore.MAX_ENTRY);
        assertThat(store.get("load-big").orElseThrow().facts().get(0).value()).endsWith("…");
    }
}
