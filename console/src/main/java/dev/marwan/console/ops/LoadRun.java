package dev.marwan.console.ops;

/**
 * One load run, in the terms the page shows.
 *
 * <h2>Why {@code reason} and {@code message} are separate, and both verbatim</h2>
 * When a run will not start, the useful thing is not that it failed — it is
 * <em>Insufficient cpu</em>, or <em>exceeded quota: compute-deploy, requested:
 * requests.cpu=800m, used: requests.cpu=2900m, limited: requests.cpu=3</em>.
 * Those sentences are written by the scheduler and by the quota admission
 * controller about this cluster at this moment, and they are the content. The
 * console copies them out rather than replacing them with a phrase of its own,
 * because a paraphrase of something it did not observe would be worth less than
 * the original and would be the console's opinion rather than the cluster's.
 *
 * @param available  whether the cluster could be read at all; false is ordinary
 *                   on a laptop and brief inside a cluster
 * @param detail     why not, when it could not
 * @param jobName    the Job, named after the drop — which is what enforces one
 *                   run per drop, since a second create of the same name is
 *                   refused by the API server rather than by a counter the
 *                   console would have to keep correct
 * @param reason     the condition or event reason, e.g. {@code Unschedulable}
 * @param message    the full sentence behind that reason
 * @param cpuMillis  what this run asks the scheduler for, so a Pending run can
 *                   be read against the free budget beside it
 */
public record LoadRun(
        boolean available,
        String detail,
        String dropId,
        String jobName,
        Phase phase,
        int vus,
        int cpuMillis,
        String reason,
        String message,
        long secondsElapsed) {

    public enum Phase {
        /** No Job by this name: nothing has been run for this drop, or it has been reaped. */
        NONE,
        /** The Job exists and no pod of it is running yet. The reason says why. */
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public static LoadRun none(String dropId, String jobName) {
        return new LoadRun(true, null, dropId, jobName, Phase.NONE, 0, 0, null, null, 0);
    }

    public static LoadRun unavailable(String dropId, String detail) {
        return new LoadRun(false, detail, dropId, null, Phase.NONE, 0, 0, null, null, 0);
    }
}
