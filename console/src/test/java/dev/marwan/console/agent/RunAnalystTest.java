package dev.marwan.console.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunAnalystTest {

    static final Instant NOW = Instant.parse("2026-09-25T14:00:00Z");

    FakeCluster cluster;
    AnalysisStore store;
    List<RunWindow> analysed;

    @BeforeEach
    void setUp() {
        cluster = new FakeCluster();
        store = new AnalysisStore(new AnalysisStoreTest.FakePort());
        analysed = new ArrayList<>();
    }

    RunAnalyst reconciler() {
        AnalystTest.Scripted model = new AnalystTest.Scripted();
        Analyst analyst = new Analyst(w -> {
            analysed.add(w);
            Facts f = new Facts();
            f.add("invariant", "Seats oversold", "0");
            return f;
        }, new Tools(cluster, (q, l, s, e, st) -> List.of()), model, Clock.fixed(NOW, ZoneOffset.UTC));
        return new RunAnalyst(cluster, analyst, store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static Job withDropEnv(Job job, String drop) {
        job.setSpec(new io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder().withNewTemplate().withNewSpec()
                .addNewContainer().withName("k6").withEnv(new EnvVarBuilder().withName("DROP_ID").withValue(drop).build())
                .endContainer().endSpec().endTemplate().build());
        return job;
    }

    @Test
    void analysesTheOldestFinishedRunWithoutAReportOnePerTick() {
        cluster.jobs.add(withDropEnv(FakeCluster.loadJob("load-new", "new", NOW.minusSeconds(300), NOW.minusSeconds(200), false), "New"));
        cluster.jobs.add(withDropEnv(FakeCluster.loadJob("load-old", "old", NOW.minusSeconds(900), NOW.minusSeconds(800), false), "Old"));
        RunAnalyst r = reconciler();

        r.reconcileOnce();

        assertThat(analysed).hasSize(1);
        assertThat(analysed.get(0).job()).isEqualTo("load-old");
        assertThat(analysed.get(0).dropId()).isEqualTo("Old");
        assertThat(analysed.get(0).end()).isEqualTo(NOW.minusSeconds(800).plusSeconds(30));
        r.reconcileOnce();
        assertThat(analysed).extracting(RunWindow::job).containsExactly("load-old", "load-new");
        r.reconcileOnce();
        assertThat(analysed).hasSize(2);
        assertThat(store.list()).extracting(Analysis::job).containsExactlyInAnyOrder("load-old", "load-new");
    }

    @Test
    void skipsRunningJobsOtherJobsAndRunsThatJustEnded() {
        cluster.jobs.add(FakeCluster.loadJob("load-running", "r", NOW.minusSeconds(60), null, false));
        cluster.jobs.add(FakeCluster.loadJob("load-just-done", "j", NOW.minusSeconds(90), NOW.minusSeconds(10), false));
        Job keepalive = FakeCluster.loadJob("keepalive-1", "k", NOW.minusSeconds(900), NOW.minusSeconds(800), false);
        keepalive.getMetadata().getLabels().put("app", "keepalive");
        cluster.jobs.add(keepalive);

        reconciler().reconcileOnce();

        assertThat(analysed).isEmpty();
    }

    @Test
    void aFailedRunIsAnalysedToo() {
        Job failed = FakeCluster.loadJob("load-failed", "f", NOW.minusSeconds(900), null, true);
        failed.getStatus().setConditions(List.of(new io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder()
                .withType("Failed").withStatus("True").withLastTransitionTime(NOW.minusSeconds(600).toString()).build()));
        cluster.jobs.add(failed);

        reconciler().reconcileOnce();

        assertThat(analysed).extracting(RunWindow::job).containsExactly("load-failed");
        assertThat(analysed.get(0).dropId()).isEqualTo("f");
    }

    @Test
    void aSecondRushOnTheSameDropGetsItsOwnReport() {
        cluster.jobs.add(withDropEnv(FakeCluster.loadJob("load-d1", "d1", NOW.minusSeconds(900), NOW.minusSeconds(800), false), "d1"));
        RunAnalyst r = reconciler();
        r.reconcileOnce();
        // The drop's Job is deleted and recreated under the same name for the next rush.
        cluster.jobs.clear();
        cluster.jobs.add(withDropEnv(FakeCluster.loadJob("load-d1", "d1", NOW.minusSeconds(300), NOW.minusSeconds(200), false), "d1"));
        r.reconcileOnce();

        assertThat(analysed).extracting(RunWindow::start).containsExactly(NOW.minusSeconds(900), NOW.minusSeconds(300));
        assertThat(store.list()).hasSize(2);
    }

    @Test
    void aRunThatKeepsFailingIsGivenUpAfterThreeTries() {
        cluster.jobs.add(withDropEnv(FakeCluster.loadJob("load-bad", "b", NOW.minusSeconds(900), NOW.minusSeconds(800), false), "b"));
        int[] attempts = {0};
        Analyst exploding = new Analyst(w -> {
            attempts[0]++;
            throw new IllegalStateException("boom");
        }, new Tools(cluster, (q, l, s, e, st) -> List.of()), new AnalystTest.Scripted(), Clock.fixed(NOW, ZoneOffset.UTC));
        RunAnalyst r = new RunAnalyst(cluster, exploding, store, Clock.fixed(NOW, ZoneOffset.UTC));
        for (int i = 0; i < 10; i++) {
            r.reconcileOnce();
        }
        assertThat(attempts[0]).isEqualTo(3);
    }

    @Test
    void aTwoWaveJobGivesATwoWaveWindow() {
        Job job = withDropEnv(FakeCluster.loadJob("load-two", "t", NOW.minusSeconds(900), NOW.minusSeconds(500), false), "t");
        job.getMetadata().setAnnotations(new java.util.HashMap<>(java.util.Map.of(
                "rembayung.dev/waves", "2", "rembayung.dev/wave-gap-seconds", "180")));
        cluster.jobs.add(job);

        reconciler().reconcileOnce();

        assertThat(analysed).singleElement().satisfies(w -> {
            assertThat(w.waves()).isEqualTo(2);
            assertThat(w.waveGapSeconds()).isEqualTo(180);
        });
    }

    @Test
    void aClusterThatCannotBeReadIsSkippedQuietly() {
        RunAnalyst r = new RunAnalyst(new FakeCluster() {
            @Override
            public List<Job> jobs() {
                throw new IllegalStateException("API down");
            }
        }, null, store, Clock.fixed(NOW, ZoneOffset.UTC));
        r.reconcileOnce();
        assertThat(store.list()).isEmpty();
    }
}
