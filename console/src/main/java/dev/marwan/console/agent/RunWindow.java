package dev.marwan.console.agent;

import java.time.Instant;

/**
 * The run being analysed: its Job, its drop, and the time the facts cover -
 * from the Job's start to thirty seconds after it ended, so the tail of the
 * rush (the last bookings, the autoscaler settling) is inside it.
 */
public record RunWindow(String job, String dropId, Instant start, Instant end, int waves, int waveGapSeconds,
                        String wave2DropId) {

    /** A one-wave run. */
    public RunWindow(String job, String dropId, Instant start, Instant end) {
        this(job, dropId, start, end, 1, 0, null);
    }

    /** A run whose second wave's sitting is not known. */
    public RunWindow(String job, String dropId, Instant start, Instant end, int waves, int waveGapSeconds) {
        this(job, dropId, start, end, waves, waveGapSeconds, null);
    }

    /** When wave n (1 or 2) began: the run's start, plus the gap for wave 2. */
    public Instant waveStart(int n) {
        return start.plusSeconds((long) (n - 1) * waveGapSeconds);
    }

    public String key() {
        return key(job, start);
    }

    static String key(String job, Instant start) {
        return job + "-" + start.getEpochSecond();
    }
}
