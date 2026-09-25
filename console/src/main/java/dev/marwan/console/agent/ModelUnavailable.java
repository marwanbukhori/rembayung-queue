package dev.marwan.console.agent;

/** The model did not answer usefully: unreachable, refused, too slow, or empty. */
public class ModelUnavailable extends RuntimeException {
    public ModelUnavailable(String message) {
        super(message);
    }
}
