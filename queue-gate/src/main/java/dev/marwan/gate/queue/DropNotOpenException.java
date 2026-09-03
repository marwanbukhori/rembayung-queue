package dev.marwan.gate.queue;

public class DropNotOpenException extends RuntimeException {

    private final long secondsUntilOpen;

    public DropNotOpenException(long secondsUntilOpen) {
        super("Drop opens in " + secondsUntilOpen + " seconds");
        this.secondsUntilOpen = secondsUntilOpen;
    }

    public long getSecondsUntilOpen() { return secondsUntilOpen; }
}
