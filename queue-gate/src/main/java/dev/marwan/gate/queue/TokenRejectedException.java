package dev.marwan.gate.queue;

public class TokenRejectedException extends RuntimeException {

    private final String reason;

    public TokenRejectedException(String reason) {
        super("Admission token rejected: " + reason);
        this.reason = reason;
    }

    public String getReason() { return reason; }
}
