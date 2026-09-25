package dev.marwan.console.agent;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Every report, in one ConfigMap, newest twelve kept.
 *
 * One object rather than one per run: the console may get, create and update
 * ConfigMaps but not list or delete them, and CD - by design - cannot change
 * that. A single map needs neither, and evicting the oldest key is its own
 * pruning. Each entry is capped at 60 KB, so twelve stay well inside the
 * 1 MiB an object may hold.
 *
 * Writes carry the resourceVersion they read, so two writers cannot silently
 * overwrite each other: a conflict re-reads and tries once more.
 */
public class AnalysisStore {

    static final String NAME = "run-analyses";
    static final int KEEP = 12;
    static final int MAX_ENTRY = 60_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The three ConfigMap calls this needs, so it is tested without a cluster. */
    public interface ConfigMapPort {
        Optional<ConfigMap> get(String name);

        void create(ConfigMap map);

        /** Throws {@link Conflict} when the resourceVersion is stale. */
        void update(ConfigMap map);
    }

    public static class Conflict extends RuntimeException { }

    private final ConfigMapPort port;

    public AnalysisStore(ConfigMapPort port) {
        this.port = port;
    }

    /** The stored runs' keys: job name and start time. */
    public Set<String> keys() {
        return data().keySet();
    }

    /** By key, or by Job name - then the newest run of that name, which is the Job that exists now. */
    public Optional<Analysis> get(String id) {
        Map<String, String> data = data();
        if (data.containsKey(id)) {
            return read(data.get(id));
        }
        return data.values().stream().map(AnalysisStore::read).flatMap(Optional::stream)
                .filter(a -> a.job().equals(id)).max(Comparator.comparing(Analysis::end));
    }

    /** Newest run first - by when it ran, so re-analysing an old one does not move it. */
    public List<Analysis> list() {
        return data().values().stream().map(AnalysisStore::read).flatMap(Optional::stream)
                .sorted(Comparator.comparing(Analysis::end).reversed()).toList();
    }

    public void put(Analysis analysis) {
        String entry = fit(analysis);
        try {
            write(analysis.key(), entry);
        } catch (Conflict e) {
            write(analysis.key(), entry);
        }
    }

    private void write(String job, String entry) {
        Optional<ConfigMap> existing = port.get(NAME);
        Map<String, String> data = new LinkedHashMap<>(existing.map(ConfigMap::getData).orElse(Map.of()));
        data.put(job, entry);
        while (data.size() > KEEP) {
            data.entrySet().stream()
                    .min(Comparator.comparing(e -> read(e.getValue()).map(Analysis::end)
                            .orElse(java.time.Instant.EPOCH)))
                    .ifPresent(oldest -> data.remove(oldest.getKey()));
        }
        if (existing.isEmpty()) {
            port.create(new ConfigMapBuilder().withNewMetadata().withName(NAME)
                    .addToLabels("app.kubernetes.io/component", "run-analysis").endMetadata().withData(data).build());
        } else {
            ConfigMap map = existing.get();
            map.setData(data);
            port.update(map);
        }
    }

    private Map<String, String> data() {
        return port.get(NAME).map(ConfigMap::getData).orElse(Map.of());
    }

    /** The analysis as JSON, its fact values shortened until it fits. */
    static String fit(Analysis a) {
        String json = JSON.writeValueAsString(a);
        for (int limit = 2000; json.length() > MAX_ENTRY && limit >= 100; limit /= 2) {
            int cap = limit;
            List<Fact> shorter = new ArrayList<>();
            for (Fact f : a.facts()) {
                shorter.add(f.value().length() > cap
                        ? new Fact(f.id(), f.source(), f.label(), f.value().substring(0, cap) + "…") : f);
            }
            a = new Analysis(a.job(), a.dropId(), a.start(), a.end(), shorter, a.trail(), a.report(), a.model(),
                    a.source(), a.note(), a.problems(), a.analysedAt(), a.millis());
            json = JSON.writeValueAsString(a);
        }
        return json;
    }

    private static Optional<Analysis> read(String json) {
        try {
            return Optional.of(JSON.readValue(json, Analysis.class));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}
