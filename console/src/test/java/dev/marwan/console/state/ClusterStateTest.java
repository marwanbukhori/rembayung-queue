package dev.marwan.console.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterStateTest {

    // The console must render a partial picture rather than nothing when the
    // Kubernetes API is unreachable — the demo's own state still matters even
    // when cluster introspection is unavailable.
    @Test
    void clusterStateDegradesToUnavailableRatherThanThrowing() {
        ClusterState state = ClusterState.unavailable("API server refused");

        assertThat(state.available()).isFalse();
        assertThat(state.detail()).contains("API server");
    }

    /**
     * An unavailable state still has to be safe to draw. The page walks these
     * lists whatever `available` says, so nulls here would move an outage in
     * the cluster into a crash in the browser — the console becoming the second
     * thing broken, which is the failure mode this whole design avoids.
     */
    @Test
    void anUnavailableStateIsStillSafeToRender() {
        ClusterState state = ClusterState.unavailable("connection refused");

        assertThat(state.consumers()).isEmpty();
        assertThat(state.autoscalers()).isEmpty();
    }

    /**
     * The free figure is what a Pending run is read against: "this job wants
     * 800m, 100m is free" is the whole explanation, and it has to be the
     * quota's own arithmetic rather than the page's.
     */
    @Test
    void theQuotaReportsWhatIsLeftAndHowFullItIs() {
        ClusterState.Quota quota = new ClusterState.Quota("compute-deploy", 2900, 3000);

        assertThat(quota.freeMillis()).isEqualTo(100);
        assertThat(quota.percent()).isEqualTo(97);
    }

    /**
     * A quota over its hard limit is a real state — a limit lowered underneath
     * running pods does it — and it must not draw a bar past the end of its
     * track or report a negative amount of free CPU.
     */
    @Test
    void aQuotaOverItsLimitClampsRatherThanGoingNegative() {
        ClusterState.Quota quota = new ClusterState.Quota("compute-deploy", 3600, 3000);

        assertThat(quota.freeMillis()).isZero();
        assertThat(quota.percent()).isEqualTo(100);
    }

    /**
     * The pool figure is arithmetic on two numbers this project chose: four
     * replicas at a Hikari pool of five is twenty connections, against an
     * Oracle Always Free cap of about twenty. At that point the pool is the
     * binding constraint, which is what 200 admissions a second is for.
     */
    @Test
    void fourReplicasAtFiveConnectionsEachSaturatesTheOracleCap() {
        ClusterState.Pool pool = new ClusterState.Pool("booking-service", 4, 5, 20);

        assertThat(pool.connections()).isEqualTo(20);
        assertThat(pool.saturated()).isTrue();
        assertThat(pool.percent()).isEqualTo(100);
    }

    @Test
    void theProductionTwoReplicasLeaveHeadroom() {
        ClusterState.Pool pool = new ClusterState.Pool("booking-service", 2, 5, 20);

        assertThat(pool.connections()).isEqualTo(10);
        assertThat(pool.saturated()).isFalse();
    }

    /** A quota with no hard limit must not divide by zero on the way to a bar. */
    @Test
    void anUnboundedQuotaDoesNotDivideByZero() {
        assertThat(new ClusterState.Quota("none", 700, 0).percent()).isZero();
        assertThat(new ClusterState.Pool("booking-service", 2, 5, 0).percent()).isZero();
    }

    @Test
    void anAvailableStateCarriesNoReason() {
        ClusterState state = ClusterState.of("marwanbukhori-dev", 
                new ClusterState.Quota("compute-deploy", 700, 3000),
                List.of(new ClusterState.Consumer("queue-gate", 200, 2)),
                List.of(),
                new ClusterState.Pool("booking-service", 2, 5, 20));

        assertThat(state.available()).isTrue();
        assertThat(state.detail()).isNull();
    }
}
