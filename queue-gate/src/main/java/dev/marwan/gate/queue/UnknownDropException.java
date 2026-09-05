package dev.marwan.gate.queue;

/** Raised when a request names a drop that has expired or never existed. */
public class UnknownDropException extends RuntimeException {
    public UnknownDropException(String dropId) {
        super("unknown drop: " + dropId);
    }
}
