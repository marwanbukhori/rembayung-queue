package dev.marwan.gate.queue;

public class DropClosedException extends RuntimeException {
    public DropClosedException() {
        super("The drop has closed");
    }
}
