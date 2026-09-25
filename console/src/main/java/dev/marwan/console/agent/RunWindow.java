package dev.marwan.console.agent;

import java.time.Instant;

/**
 * The run being analysed: its Job, its drop, and the time the facts cover -
 * from the Job's start to thirty seconds after it ended, so the tail of the
 * rush (the last bookings, the autoscaler settling) is inside it.
 */
public record RunWindow(String job, String dropId, Instant start, Instant end) {

    public String key() {
        return key(job, start);
    }

    static String key(String job, Instant start) {
        return job + "-" + start.getEpochSecond();
    }
}
