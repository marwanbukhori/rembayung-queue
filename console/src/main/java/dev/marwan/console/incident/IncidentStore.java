package dev.marwan.console.incident;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.marwan.console.agent.AnalysisStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Incidents, in one ConfigMap, the newest twelve kept - the same shape and the
 * same rules as the agent's reports: a clean object on every write, carrying
 * the resourceVersion it read, and one retry on a conflict.
 */
public class IncidentStore {

    static final String NAME = "incidents";
    static final int KEEP = 12;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnalysisStore.ConfigMapPort port;

    public IncidentStore(AnalysisStore.ConfigMapPort port) {
        this.port = port;
    }

    public Optional<Incident> open() {
        return list().stream().filter(Incident::isOpen).findFirst();
    }

    public Optional<Incident> get(String id) {
        return Optional.ofNullable(data().get(id)).flatMap(IncidentStore::read);
    }

    /** Newest first. */
    public List<Incident> list() {
        return data().values().stream().map(IncidentStore::read).flatMap(Optional::stream)
                .sorted(Comparator.comparing((Incident i) -> i.openedAt).reversed()).toList();
    }

    public void put(Incident incident) {
        try {
            write(incident);
        } catch (AnalysisStore.Conflict e) {
            write(incident);
        }
    }

    private void write(Incident incident) {
        Optional<ConfigMap> existing = port.get(NAME);
        Map<String, String> data = new LinkedHashMap<>(existing.map(ConfigMap::getData).orElse(Map.of()));
        data.put(incident.id, JSON.writeValueAsString(incident));
        while (data.size() > KEEP) {
            data.entrySet().stream()
                    .min(Comparator.comparing(e -> read(e.getValue()).map(i -> i.openedAt).orElse(java.time.Instant.EPOCH)))
                    .ifPresent(oldest -> data.remove(oldest.getKey()));
        }
        ConfigMap map = new ConfigMapBuilder().withNewMetadata().withName(NAME)
                .addToLabels("app.kubernetes.io/component", "incidents")
                .withResourceVersion(existing.map(c -> c.getMetadata().getResourceVersion()).orElse(null))
                .endMetadata().withData(data).build();
        if (existing.isEmpty()) {
            port.create(map);
        } else {
            port.update(map);
        }
    }

    private Map<String, String> data() {
        return port.get(NAME).map(ConfigMap::getData).orElse(Map.of());
    }

    private static Optional<Incident> read(String json) {
        try {
            return Optional.of(JSON.readValue(json, Incident.class));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}
