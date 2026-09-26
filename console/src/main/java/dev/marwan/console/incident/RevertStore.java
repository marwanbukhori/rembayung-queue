package dev.marwan.console.incident;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.marwan.console.agent.AnalysisStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Temporary autoscaler raises, and the minimum each one goes back to.
 *
 * One entry per autoscaler, holding the minimum it had BEFORE the first raise.
 * A second raise while the first is pending extends the deadline and keeps that
 * original - re-reading the minimum then would record the raise itself as the
 * thing to return to, and the autoscaler would stay large for good. Kept apart
 * from incidents, so an incident falling out of the newest twelve cannot take
 * a pending revert with it.
 */
public class RevertStore {

    static final String NAME = "remediation-reverts";
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Pending(String hpa, int original, Instant until, String incident) { }

    private final AnalysisStore.ConfigMapPort maps;

    public RevertStore(AnalysisStore.ConfigMapPort maps) {
        this.maps = maps;
    }

    /** Record a raise: the current minimum is the original only if no raise is already pending. */
    public void raise(String hpa, int currentMinimum, Instant until, String incident) {
        try {
            write(hpa, currentMinimum, until, incident);
        } catch (AnalysisStore.Conflict e) {
            write(hpa, currentMinimum, until, incident);
        }
    }

    public List<Pending> pending() {
        return maps.get(NAME).map(RevertStore::parse).orElse(List.of());
    }

    public void remove(String hpa) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<ConfigMap> existing = maps.get(NAME);
            if (existing.isEmpty() || existing.get().getData() == null || !existing.get().getData().containsKey(hpa)) {
                return;
            }
            Map<String, String> data = new LinkedHashMap<>(existing.get().getData());
            data.remove(hpa);
            try {
                maps.update(clean(data, existing));
                return;
            } catch (AnalysisStore.Conflict e) {
                // Someone wrote between our read and write; read again once.
            }
        }
    }

    private void write(String hpa, int currentMinimum, Instant until, String incident) {
        Optional<ConfigMap> existing = maps.get(NAME);
        Map<String, String> data = new LinkedHashMap<>(existing.map(ConfigMap::getData).orElse(Map.of()));
        Optional<Pending> already = existing.map(RevertStore::parse).orElse(List.of()).stream()
                .filter(p -> p.hpa().equals(hpa)).findFirst();
        int original = already.map(Pending::original).orElse(currentMinimum);
        Instant deadline = already.map(p -> p.until().isAfter(until) ? p.until() : until).orElse(until);
        data.put(hpa, JSON.writeValueAsString(Map.of("original", original, "until", deadline.toString(),
                "incident", incident == null ? "" : incident)));
        ConfigMap map = clean(data, existing);
        if (existing.isEmpty()) {
            maps.create(map);
        } else {
            maps.update(map);
        }
    }

    /** A fresh object with only what this owns: fabric8 cannot re-send what the API returned. */
    private static ConfigMap clean(Map<String, String> data, Optional<ConfigMap> existing) {
        return new ConfigMapBuilder().withNewMetadata().withName(NAME)
                .addToLabels("app.kubernetes.io/component", "remediation")
                .withResourceVersion(existing.map(c -> c.getMetadata().getResourceVersion()).orElse(null))
                .endMetadata().withData(data).build();
    }

    private static List<Pending> parse(ConfigMap map) {
        List<Pending> out = new ArrayList<>();
        if (map.getData() == null) {
            return out;
        }
        map.getData().forEach((hpa, json) -> {
            try {
                JsonNode n = JSON.readTree(json);
                out.add(new Pending(hpa, n.path("original").asInt(), Instant.parse(n.path("until").asString()),
                        n.path("incident").asString("")));
            } catch (RuntimeException e) {
                // A malformed entry is skipped rather than blocking every other revert.
            }
        });
        return out;
    }
}
