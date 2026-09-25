package dev.marwan.console.objects;

/** An unknown kind, an absent object, or one that is not this project's. All three answer 404. */
public class ObjectNotFound extends RuntimeException {

    public ObjectNotFound(String message) {
        super(message);
    }
}
