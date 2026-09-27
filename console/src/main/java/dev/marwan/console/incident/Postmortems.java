package dev.marwan.console.incident;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes sure every closed incident ends up with a postmortem.
 *
 * One is written when an incident closes, off the watcher's lock because a
 * model call can take a minute. A console that restarts meanwhile loses that
 * write, so a sweep on the commander's schedule writes any that are missing.
 * An incident already being written is not written twice.
 */
public class Postmortems {

    private final PostmortemWriter writer;
    private final IncidentStore store;
    private final IncidentWatcher watcher;
    private final Set<String> writing = ConcurrentHashMap.newKeySet();

    public Postmortems(PostmortemWriter writer, IncidentStore store, IncidentWatcher watcher) {
        this.writer = writer;
        this.store = store;
        this.watcher = watcher;
    }

    public void write(Incident closed) {
        if (!writing.add(closed.id)) {
            return;
        }
        try {
            Incident.Postmortem pm = writer.write(closed);
            watcher.update(closed.id, i -> {
                if (i.postmortem == null) {
                    i.postmortem = pm;
                }
            });
        } finally {
            writing.remove(closed.id);
        }
    }

    public void backfill() {
        store.list().stream().filter(i -> !i.isOpen() && i.postmortem == null).forEach(this::write);
    }
}
