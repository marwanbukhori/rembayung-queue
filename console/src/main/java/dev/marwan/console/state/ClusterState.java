package dev.marwan.console.state;

import java.util.List;

/**
 * What the cluster will and will not give you, live.
 *
 * <h2>Constraints are content</h2>
 * This record exists because a load run that cannot start is the most
 * interesting thing this console can show. A visitor whose Job sits
 * {@code Pending} because the namespace is out of CPU has been shown a real
 * quota, a real scheduler decision and a real operational trade — which is
 * worth more than a green tick. So this is not error handling: it is the
 * feature, and it names the limit and what is consuming it rather than saying
 * something went wrong.
 *
 * <h2>Unavailable is a value, not an exception</h2>
 * The Kubernetes API being briefly unreachable is ordinary inside a cluster and
 * permanent on a laptop. Either way the drop's own numbers still matter, so
 * this degrades to {@link #unavailable(String)} carrying the reason and the
 * page keeps its other panels. Nothing here ever propagates a 500.
 */
public record ClusterState(
        boolean available,
        String detail,
        /**
         * The namespace these figures were actually read from.
         *
         * Reported rather than assumed. The UI previously printed a hardcoded
         * "ns/rembayung" while the backend read marwanbukhori-dev, so the header
         * named a namespace that does not exist — a label confidently describing
         * the wrong cluster is worse than no label.
         */
        String namespace,
        Quota quota,
        List<Consumer> consumers,
        List<Autoscaler> autoscalers,
        Pool pool) {

    public static ClusterState of(String namespace, Quota quota, List<Consumer> consumers,
                                  List<Autoscaler> autoscalers, Pool pool) {
        return new ClusterState(true, null, namespace, quota, consumers, autoscalers, pool);
    }

    public static ClusterState unavailable(String detail) {
        return new ClusterState(false, detail, null, null, List.of(), List.of(), null);
    }

    /**
     * The namespace CPU budget: what has been requested against what the quota
     * will allow.
     *
     * Millicores rather than cores because that is the unit every other number
     * on the page and in {@code oc describe} is written in, and because integer
     * millicores cannot drift the way a rounded decimal core count does.
     *
     * @param name         the ResourceQuota object, so the reader can go and run
     *                     {@code oc describe resourcequota} on it themselves
     * @param usedMillis   {@code status.used["requests.cpu"]}
     * @param hardMillis   {@code status.hard["requests.cpu"]}
     */
    public record Quota(String name, int usedMillis, int hardMillis, int freeMillis, int percent) {

        /**
         * The derived halves are components rather than computed accessors so
         * that they are actually in the JSON.
         *
         * A record serialises its components; a method that merely looks like a
         * getter is not one of them and silently does not appear. The browser
         * draws the bar from `percent` and writes "100m free" from
         * `freeMillis`, so computing them here — once, beside the numbers they
         * come from — is what keeps the page from having to reimplement the
         * arithmetic and get a different answer.
         */
        public Quota(String name, int usedMillis, int hardMillis) {
            this(name, usedMillis, hardMillis,
                    Math.max(0, hardMillis - usedMillis),
                    hardMillis <= 0 ? 0 : Math.min(100, (int) Math.round(usedMillis * 100.0 / hardMillis)));
        }
    }

    /**
     * One workload's share of the budget, which is the "what is consuming it"
     * half of naming a limit.
     *
     * Grouped by the workload rather than listed per pod: "queue-gate 1000m
     * across 10 pods" is the sentence that explains a Pending Job, and ten
     * lines of 100m is the same fact made unreadable.
     */
    public record Consumer(String name, int millis, int pods) { }

    /**
     * One HorizontalPodAutoscaler: where it is, where it wants to be, and the
     * ceiling it is allowed to reach.
     *
     * {@code desired} is separate from {@code current} on purpose. When they
     * disagree the autoscaler has made a decision the cluster has not yet
     * carried out, and that gap is the interesting second of the whole demo.
     *
     * @param currentPercent  observed CPU utilisation, null while no metric has
     *                        been collected yet
     * @param targetPercent   the utilisation it is scaling towards
     * @param note            the HPA's own ScalingLimited message when it is
     *                        being held back, verbatim
     */
    public record Autoscaler(
            String name,
            int current,
            int desired,
            int min,
            int max,
            Integer currentPercent,
            Integer targetPercent,
            String note) { }

    /**
     * The Oracle connection budget: replicas times the pool each one opens,
     * against what the database will actually hand out.
     *
     * This is arithmetic, not a metric, and it says so. Hikari's gauges are per
     * pod and the session cap belongs to Oracle Always Free rather than to us,
     * so the console multiplies two numbers it knows and shows both — which is
     * honest in a way an invented live figure would not be.
     *
     * When {@code connections} reaches {@code cap} the pool is the binding
     * constraint: admission at 200/s produces 503 with Retry-After, and
     * oversold stays at zero anyway. That last part is the claim the whole
     * project makes.
     */
    public record Pool(String deployment, int replicas, int perReplica, int cap,
                       int connections, boolean saturated, int percent) {

        /** Components rather than computed accessors, for the reason given on Quota. */
        public Pool(String deployment, int replicas, int perReplica, int cap) {
            this(deployment, replicas, perReplica, cap,
                    replicas * perReplica,
                    replicas * perReplica >= cap,
                    cap <= 0 ? 0 : Math.min(100, (int) Math.round(replicas * perReplica * 100.0 / cap)));
        }
    }
}
