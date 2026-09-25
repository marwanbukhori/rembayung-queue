package dev.marwan.console.agent;

import java.time.Duration;
import java.util.List;

/** A chat model. An interface so the loop is tested with scripted answers. */
public interface Model {

    /** The assistant's reply text, or {@link ModelUnavailable}. */
    String chat(List<Message> messages, Duration timeout);

    /** The name reported with each analysis. */
    String name();
}
