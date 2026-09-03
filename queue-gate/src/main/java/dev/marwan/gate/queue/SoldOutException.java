package dev.marwan.gate.queue;

public class SoldOutException extends RuntimeException {
    public SoldOutException() {
        super("The drop is sold out");
    }
}
